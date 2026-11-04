package com.aiops.service;

import com.aiops.entity.ServerAsset;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * 流式 SSH 执行（WebSocket 使用）：边读边回调 stdout/stderr，支持中途 kill。
 */
@Service
public class StreamingSshService {

    private static final Logger log = LoggerFactory.getLogger(StreamingSshService.class);
    private static final int MAX_STREAM_CHARS = 1_000_000;

    private final PasswordEncryptor passwordEncryptor;

    public StreamingSshService(PasswordEncryptor passwordEncryptor) {
        this.passwordEncryptor = passwordEncryptor;
    }

    public interface OutputCallback {
        void onOutput(String stream, String data);
    }

    public static class StreamHandle {
        private volatile boolean killed;
        private volatile ChannelExec channel;
        private volatile Session session;

        void bind(Session session, ChannelExec channel) {
            this.session = session;
            this.channel = channel;
        }

        public boolean isKilled() { return killed; }

        public void kill() {
            killed = true;
            try { if (channel != null) channel.disconnect(); } catch (Exception ignore) {}
            try { if (session != null) session.disconnect(); } catch (Exception ignore) {}
        }
    }

    public SshResult stream(ServerAsset server, String command, StreamHandle handle, OutputCallback callback) {
        long startedAt = System.currentTimeMillis();
        Session session = null;
        ChannelExec channel = null;
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        int[] errBudget = {MAX_STREAM_CHARS};

        try {
            JSch jsch = new JSch();
            String host = server.getIp();
            int port = server.getSshPort() != null ? server.getSshPort() : 22;
            String user = server.getSshUser() != null ? server.getSshUser() : "root";
            String password = resolvePassword(server);
            String sshKey = server.getSshKey();

            if (sshKey != null && !sshKey.trim().isEmpty()) {
                jsch.addIdentity("ssh-key", sshKey.getBytes(StandardCharsets.UTF_8), null, null);
                session = jsch.getSession(user, host, port);
            } else {
                session = jsch.getSession(user, host, port);
                session.setPassword(password);
            }
            Properties config = new Properties();
            config.put("StrictHostKeyChecking", "no");
            config.put("PreferredAuthentications",
                    sshKey != null && !sshKey.trim().isEmpty()
                            ? "publickey,password,keyboard-interactive"
                            : "password,keyboard-interactive,publickey");
            session.setConfig(config);
            session.connect(8000);

            channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(command);
            channel.setInputStream(null);
            handle.bind(session, channel);

            final StreamHandle h = handle;
            channel.setErrStream(new OutputStream() {
                @Override
                public void write(int b) {}
                @Override
                public void write(byte[] b, int off, int len) {
                    if (h.isKilled() || len <= 0) return;
                    int take = Math.min(len, errBudget[0]);
                    if (take <= 0) return;
                    String s = new String(b, off, take, StandardCharsets.UTF_8);
                    errBudget[0] -= take;
                    stderr.append(s);
                    callback.onOutput("stderr", s);
                }
            });

            InputStream in = channel.getInputStream();
            channel.connect(8000);

            InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
            char[] buf = new char[4096];
            int total = 0;
            int n;
            while ((n = reader.read(buf)) != -1) {
                if (handle.isKilled()) break;
                if (n <= 0) continue;
                if (total >= MAX_STREAM_CHARS) continue;
                int take = Math.min(n, MAX_STREAM_CHARS - total);
                String chunk = new String(buf, 0, take);
                total += take;
                stdout.append(chunk);
                callback.onOutput("stdout", chunk);
            }

            // 等待退出码（kill 时 channel/session 会被断开）
            int wait = 0;
            while (!channel.isClosed() && !handle.isKilled() && wait < 1200) {
                Thread.sleep(100);
                wait++;
            }
            int exitCode = handle.isKilled() ? -1 : channel.getExitStatus();

            SshResult r = new SshResult();
            r.setServerId(server.getId());
            r.setHostname(server.getHostname());
            r.setIp(server.getIp());
            r.setEnvironment(server.getEnvironment());
            r.setExitCode(exitCode);
            r.setSuccess(!handle.isKilled() && exitCode == 0);
            r.setStdout(stdout.toString());
            r.setStderr(stderr.toString());
            r.setDurationMs(System.currentTimeMillis() - startedAt);
            r.setKilled(handle.isKilled());
            if (handle.isKilled()) r.setErrorMessage("执行已被用户终止");
            return r;
        } catch (Exception e) {
            SshResult r = new SshResult();
            r.setServerId(server.getId());
            r.setHostname(server.getHostname());
            r.setIp(server.getIp());
            r.setEnvironment(server.getEnvironment());
            r.setExitCode(-1);
            r.setSuccess(false);
            r.setKilled(handle.isKilled());
            r.setStdout(stdout.toString());
            r.setStderr(stderr.toString());
            r.setErrorMessage(handle.isKilled() ? "执行已被用户终止" : ("SSH连接或执行失败: " + e.getMessage()));
            r.setDurationMs(System.currentTimeMillis() - startedAt);
            log.warn("流式 SSH 执行失败 {}@{}: {}", server.getSshUser(), server.getIp(), e.getMessage());
            return r;
        } finally {
            try { if (channel != null && channel.isConnected()) channel.disconnect(); } catch (Exception ignore) {}
            try { if (session != null && session.isConnected()) session.disconnect(); } catch (Exception ignore) {}
        }
    }

    private String resolvePassword(ServerAsset server) {
        String enc = server.getSshPasswordEncrypted();
        if (enc != null && !enc.isEmpty()) {
            try { return passwordEncryptor.decrypt(enc); }
            catch (Exception e) { throw new RuntimeException("服务器密码解密失败，请重新设置", e); }
        }
        return server.getSshPassword() != null ? server.getSshPassword() : "";
    }
}