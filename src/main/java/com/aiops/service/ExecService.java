package com.aiops.service;

import java.util.stream.Collectors;
import com.aiops.entity.ServerAsset;
import com.aiops.repository.ServerAssetRepository;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import javax.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * SSH远程命令执行服务
 * 使用JSch库进行SSH连接和命令执行
 * 连接超时5秒，命令执行超时10秒
 */
@Service
public class ExecService {

    private static final Logger log = LoggerFactory.getLogger(ExecService.class);

    private final ServerAssetRepository serverAssetRepository;
    private final ThreadPoolTaskExecutor sshExecutor;
    private final MeterRegistry meterRegistry;
    private final PasswordEncryptor passwordEncryptor;

    private Counter sshExecCounter;
    private Timer sshExecTimer;

    public ExecService(ServerAssetRepository serverAssetRepository, ThreadPoolTaskExecutor sshExecutor,
                       MeterRegistry meterRegistry, PasswordEncryptor passwordEncryptor) {
        this.serverAssetRepository = serverAssetRepository;
        this.sshExecutor = sshExecutor;
        this.meterRegistry = meterRegistry;
        this.passwordEncryptor = passwordEncryptor;
    }

    /** 解析服务器 SSH 密码：优先解密密文，兼容旧明文 */
    private String resolvePassword(ServerAsset server) {
        String enc = server.getSshPasswordEncrypted();
        if (enc != null && !enc.isEmpty()) {
            try {
                return passwordEncryptor.decrypt(enc);
            } catch (Exception e) {
                throw new RuntimeException("服务器密码解密失败，请重新设置", e);
            }
        }
        return server.getSshPassword() != null ? server.getSshPassword() : "";
    }

    @PostConstruct
    public void initMetrics() {
        sshExecCounter = Counter.builder("aiops.ssh.exec.total")
                .description("SSH命令执行总次数")
                .register(meterRegistry);
        sshExecTimer = Timer.builder("aiops.ssh.exec.duration")
                .description("SSH命令执行耗时")
                .register(meterRegistry);
    }

    // ==================== 连通性探测 ====================

    /**
     * 检查SSH连接是否可达
     * 尝试建立SSH会话并立即断开
     *
     * @param host     主机IP
     * @param port     SSH端口
     * @param user     SSH用户名
     * @param password SSH密码（和私钥二选一）
     * @param sshKey   SSH私钥内容（和密码二选一）
     * @return true=连接成功, false=连接失败
     */
    public boolean checkSshConnectivity(String host, int port, String user, String password, String sshKey) {
        Session session = null;
        try {
            JSch jsch = new JSch();
            session = createSession(jsch, host, port, user, password, sshKey);
            // 连接超时5秒
            session.connect(5000);
            log.info("SSH探测成功: {}@{}:{}", user, host, port);
            return true;
        } catch (Exception e) {
            log.warn("SSH探测失败: {}@{}:{} - {}", user, host, port, e.getMessage());
            return false;
        } finally {
            if (session != null && session.isConnected()) {
                session.disconnect();
            }
        }
    }

    // ==================== 批量命令执行 ====================

    /**
     * 在指定服务器上批量执行命令
     * 使用线程池并发SSH连接，每台服务器独立执行
     *
     * @param serverIds 服务器ID列表
     * @param command   要执行的Shell命令
     * @return 每台服务器的执行结果列表
     */
    public List<SshResult> executeOnServers(List<Long> serverIds, String command) {
        if (serverIds == null || serverIds.isEmpty()) {
            return Collections.emptyList();
        }

        // 查询服务器信息
        List<ServerAsset> servers = serverAssetRepository.findAllById(serverIds);

        if (servers.isEmpty()) {
            return Collections.emptyList();
        }

        // 并发执行，每台服务器一个独立任务
        List<CompletableFuture<SshResult>> futures = servers.stream()
                .map(server -> CompletableFuture.supplyAsync(() -> {
                    try {
                        return executeOnServer(server, command);
                    } catch (Exception e) {
                        log.error("服务器[{}]命令执行异常: {}", server.getHostname(), e.getMessage());
                        SshResult r = new SshResult();
                        r.setServerId(server.getId());
                        r.setHostname(server.getHostname());
                        r.setIp(server.getIp());
                        r.setSuccess(false);
                        r.setErrorMessage("执行异常: " + e.getMessage());
                        return r;
                    }
                }, sshExecutor))
                .collect(Collectors.toList());

        // 等待全部完成
        List<SshResult> results = new ArrayList<>();
        for (CompletableFuture<SshResult> future : futures) {
            try {
                results.add(future.get());
            } catch (Exception e) {
                log.error("等待SSH执行结果时异常", e);
                SshResult r = new SshResult();
                        r.setSuccess(false);
                        r.setErrorMessage("系统内部错误: " + e.getMessage());
                        results.add(r);
            }
        }
        return results;
    }

    // ==================== 单机命令执行 ====================

    /**
     * 在单台服务器上执行命令
     *
     * @param server  服务器实体
     * @param command Shell命令
     * @return 执行结果
     */
    private SshResult executeOnServer(ServerAsset server, String command) {
        String host = server.getIp();
        int port = server.getSshPort() != null ? server.getSshPort() : 22;
        String user = server.getSshUser() != null ? server.getSshUser() : "root";
        String password = resolvePassword(server);
        String sshKey = server.getSshKey();

        Session session = null;
        ChannelExec channel = null;

        try {
            JSch jsch = new JSch();
            session = createSession(jsch, host, port, user, password, sshKey);

            // 连接超时5秒
            session.connect(5000);

            // 打开exec通道
            channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(command);

            // 合并输出流和错误流
            channel.setInputStream(null);
            ((ChannelExec) channel).setErrStream(System.err);

            // 获取流
            InputStream in = channel.getInputStream();
            InputStream err = channel.getErrStream();

            // 连接通道（命令执行超时10秒）
            channel.connect(10000);

            // 读取标准输出
            String stdout = readStream(in);
            String stderr = readStream(err);

            // 等待命令执行完成
            while (!channel.isClosed()) {
                Thread.sleep(100);
            }

            int exitCode = channel.getExitStatus();

            log.info("SSH执行完成: {}@{} - exitCode={}", user, host, exitCode);

            SshResult r = new SshResult();
            r.setServerId(server.getId());
            r.setHostname(server.getHostname());
            r.setIp(host);
            r.setSuccess(exitCode == 0);
            r.setStdout(stdout);
            r.setStderr(stderr);
            r.setExitCode(exitCode);
            return r;

        } catch (Exception e) {
            log.error("SSH执行失败: {}@{} - {}", user, host, e.getMessage());
            SshResult r = new SshResult();
            r.setServerId(server.getId());
            r.setHostname(server.getHostname());
            r.setIp(host);
            r.setSuccess(false);
            r.setErrorMessage("SSH连接或执行失败: " + e.getMessage());
            return r;
        } finally {
            if (channel != null && channel.isConnected()) {
                channel.disconnect();
            }
            if (session != null && session.isConnected()) {
                session.disconnect();
            }
        }
    }

    // ==================== 私有工具方法 ====================

    /**
     * 创建JSch SSH会话
     */
    private Session createSession(JSch jsch, String host, int port,
                                  String user, String password, String sshKey) throws Exception {
        Session session;

        if (sshKey != null && !sshKey.trim().isEmpty()) {
            // 使用私钥认证
            jsch.addIdentity("ssh-key", sshKey.getBytes(StandardCharsets.UTF_8), null, null);
            session = jsch.getSession(user, host, port);
        } else {
            // 使用密码认证
            session = jsch.getSession(user, host, port);
            session.setPassword(password);
        }

        // 跳过主机密钥检查（开发/测试环境简化配置）
        java.util.Properties config = new java.util.Properties();
        config.put("StrictHostKeyChecking", "no");
        config.put("PreferredAuthentications",
                sshKey != null && !sshKey.trim().isEmpty()
                        ? "publickey,password,keyboard-interactive"
                        : "password,keyboard-interactive,publickey");
        session.setConfig(config);

        return session;
    }

    /**
     * 读取输入流内容
     */
    private String readStream(InputStream inputStream) throws Exception {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8));
        StringBuilder output = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (output.length() > 0) {
                output.append("\n");
            }
            output.append(line);
        }
        return output.toString();
    }
}