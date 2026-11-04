package com.aiops.service;

import com.aiops.common.ServiceException;
import com.aiops.entity.ServerAsset;
import com.jcraft.jsch.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 服务器 SSH/SFTP 文件传输与命令执行：
 * - Agent 安装：上传安装包 + 安装脚本，执行脚本
 * - FTP 分发：把本地暂存文件 SFTP 到目标服务器
 */
@Service
public class ServerSshTransferService {

    private static final Logger log = LoggerFactory.getLogger(ServerSshTransferService.class);

    private final ServerService serverService;

    public ServerSshTransferService(ServerService serverService) {
        this.serverService = serverService;
    }

    public Session open(ServerAsset s) throws Exception {
        if (s == null) throw new ServiceException(400, "服务器不存在");
        String host = s.getIp();
        if (host == null || host.trim().isEmpty()) throw new ServiceException(400, "服务器 IP 为空");
        String user = s.getSshUser() == null || s.getSshUser().trim().isEmpty() ? "root" : s.getSshUser().trim();
        int port = s.getSshPort() == null || s.getSshPort() <= 0 ? 22 : s.getSshPort();
        String password = serverService.decryptPassword(s);
        String key = s.getSshKey();
        if ((password == null || password.isEmpty()) && (key == null || key.trim().isEmpty())) {
            throw new ServiceException(400, "服务器未配置 SSH 密码/私钥: " + host);
        }
        JSch jsch = new JSch();
        Session session;
        if (key != null && !key.trim().isEmpty()) {
            jsch.addIdentity("key-" + s.getId(), key.getBytes(StandardCharsets.UTF_8), null, null);
            session = jsch.getSession(user, host, port);
        } else {
            session = jsch.getSession(user, host, port);
            session.setPassword(password);
        }
        Properties config = new Properties();
        config.put("StrictHostKeyChecking", "no");
        config.put("PreferredAuthentications",
                key != null && !key.trim().isEmpty() ? "publickey,password,keyboard-interactive" : "password,keyboard-interactive,publickey");
        session.setConfig(config);
        session.connect(15000);
        session.setTimeout(300000);
        return session;
    }

    public void close(Session session) {
        if (session != null && session.isConnected()) {
            try { session.disconnect(); } catch (Exception ignore) { }
        }
    }

    public ExecResult exec(Session session, String command, int timeoutSeconds) throws Exception {
        ChannelExec channel = null;
        try {
            channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(command);
            channel.setInputStream(null);
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            channel.setErrStream(err);
            InputStream in = channel.getInputStream();
            channel.connect(10000);
            StringBuilder out = new StringBuilder();
            byte[] buf = new byte[4096];
            long deadline = System.currentTimeMillis() + Math.max(10, timeoutSeconds) * 1000L;
            while (true) {
                while (in.available() > 0) {
                    int n = in.read(buf);
                    if (n <= 0) break;
                    out.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                }
                if (channel.isClosed()) break;
                if (System.currentTimeMillis() > deadline) {
                    channel.disconnect();
                    throw new ServiceException(500, "远程命令超时（" + timeoutSeconds + "s）");
                }
                Thread.sleep(120);
            }
            while (in.available() > 0) {
                int n = in.read(buf);
                if (n <= 0) break;
                out.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
            String errText = err.toString(StandardCharsets.UTF_8.name());
            if (!errText.trim().isEmpty()) out.append('\n').append(errText);
            int status = channel.getExitStatus();
            return new ExecResult(status, out.toString());
        } finally {
            if (channel != null && channel.isConnected()) channel.disconnect();
        }
    }

    public String exec(ServerAsset server, String command, int timeoutSeconds) throws Exception {
        Session session = open(server);
        try {
            ExecResult r = exec(session, command, timeoutSeconds);
            if (r.exitStatus > 0) {
                throw new ServiceException(500, "远程命令失败(exit " + r.exitStatus + "): " + abbreviate(r.output, 500));
            }
            return r.output;
        } finally {
            close(session);
        }
    }

    /** 先创建远程目录，再上传（覆盖） */
    public void upload(Session session, Path localFile, String remotePath) throws Exception {
        String remoteDir = parent(remotePath);
        mkdirs(session, remoteDir);
        ChannelSftp sftp = null;
        try {
            sftp = (ChannelSftp) session.openChannel("sftp");
            sftp.connect(10000);
            try (InputStream in = Files.newInputStream(localFile)) {
                sftp.put(in, remotePath);
            }
        } finally {
            if (sftp != null && sftp.isConnected()) sftp.disconnect();
        }
    }

    public void uploadText(Session session, String content, String remotePath) throws Exception {
        String remoteDir = parent(remotePath);
        mkdirs(session, remoteDir);
        ChannelSftp sftp = null;
        try {
            sftp = (ChannelSftp) session.openChannel("sftp");
            sftp.connect(10000);
            sftp.put(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), remotePath);
        } finally {
            if (sftp != null && sftp.isConnected()) sftp.disconnect();
        }
    }

    public void mkdirs(Session session, String dir) throws Exception {
        if (dir == null || dir.trim().isEmpty() || "/".equals(dir)) return;
        ExecResult r = exec(session, "mkdir -p " + sh(dir), 15);
        if (r.exitStatus > 0) throw new ServiceException(500, "创建远程目录失败: " + abbreviate(r.output, 300));
    }

    public boolean exists(Session session, String path) throws Exception {
        ExecResult r = exec(session, "test -e " + sh(path) + " && echo YES || echo NO", 10);
        return r.output.contains("YES");
    }

    /**
     * FTP 分发：上传到临时文件，再原子 mv；可选 chmod。
     * @return {success,message,remotePath}
     */
    public Map<String, Object> distribute(ServerAsset server, Path localFile, String remoteDir,
                                          String fileName, boolean overwrite, String mode) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("serverId", server.getId());
        out.put("hostname", server.getHostname());
        out.put("ip", server.getIp());
        Session session = null;
        try {
            if (remoteDir == null || remoteDir.trim().isEmpty()) remoteDir = "/tmp";
            String dir = remoteDir.replaceAll("/+$", "");
            if (dir.isEmpty()) dir = "/";
            String target = ("/".equals(dir) ? "" : dir) + "/" + fileName;
            session = open(server);
            mkdirs(session, dir);
            if (!overwrite && exists(session, target)) {
                out.put("success", false);
                out.put("message", "目标文件已存在（未覆盖）");
                return out;
            }
            String tmp = target + ".aiops-tmp-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            upload(session, localFile, tmp);
            ExecResult mv = exec(session, "mv -f " + sh(tmp) + " " + sh(target), 20);
            if (mv.exitStatus > 0) {
                throw new ServiceException(500, "移动文件失败: " + abbreviate(mv.output, 300));
            }
            if (mode != null && mode.matches("^[0-7]{3,4}$")) {
                exec(session, "chmod " + mode + " " + sh(target), 10);
            }
            out.put("success", true);
            out.put("message", "上传成功");
            out.put("remotePath", target);
            return out;
        } catch (Exception e) {
            out.put("success", false);
            out.put("message", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return out;
        } finally {
            close(session);
        }
    }

    public static String sh(String s) {
        return "'" + (s == null ? "" : s.replace("'", "'\\''")) + "'";
    }

    private String parent(String path) {
        if (path == null) return "/";
        int idx = path.lastIndexOf('/');
        return idx <= 0 ? "/" : path.substring(0, idx);
    }

    private String abbreviate(String s, int max) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    public static class ExecResult {
        public final int exitStatus;
        public final String output;
        public ExecResult(int exitStatus, String output) {
            this.exitStatus = exitStatus;
            this.output = output == null ? "" : output;
        }
    }
}