package com.aiops.service;

import com.aiops.entity.ServerAsset;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 服务器 SSH 连接测试与自动探测（操作系统/CPU/内存/磁盘）。
 * 支持：已保存服务器（使用已存密码/私钥）或表单直接输入的临时凭据。
 */
@Service
@RequiredArgsConstructor
public class ServerSshService {

    private static final Logger log = LoggerFactory.getLogger(ServerSshService.class);

    private final ServerService serverService;

    /**
     * 为交互式终端创建 SSH Session（使用服务器资产中保存的密码/私钥）。
     * 调用方负责断开 Session。
     */
    public Session openSessionForServer(Long serverId, int timeoutMs) throws Exception {
        Map<String, Object> req = new HashMap<String, Object>();
        req.put("id", serverId);
        Credentials c = credentials(req);
        return connect(c, timeoutMs);
    }

    /** 服务器资产对应的 SSH 连接参数（供交互式终端展示） */
    public Map<String, Object> connectionInfo(ServerAsset s) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("serverId", s.getId());
        m.put("hostname", s.getHostname());
        m.put("ip", s.getIp());
        m.put("port", s.getSshPort() == null ? 22 : s.getSshPort());
        m.put("user", s.getSshUser() == null ? "root" : s.getSshUser());
        m.put("environment", s.getEnvironment());
        return m;
    }

    public Map<String, Object> test(Map<String, Object> req) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        long start = System.currentTimeMillis();
        Session session = null;
        try {
            Credentials c = credentials(req);
            session = connect(c, 6000);
            String result = exec(session, "echo OK", 10000);
            out.put("success", result.contains("OK"));
            out.put("message", result.contains("OK") ? "SSH 连接成功" : "SSH 连接成功，但返回异常");
        } catch (Exception e) {
            out.put("success", false);
            out.put("message", "SSH 连接失败: " + message(e));
            log.warn("SSH 测试失败 {}: {}", safeTarget(req), e.getMessage());
        } finally {
            disconnect(session);
        }
        out.put("elapsedMs", System.currentTimeMillis() - start);
        return out;
    }

    public Map<String, Object> probe(Map<String, Object> req) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        long start = System.currentTimeMillis();
        Session session = null;
        try {
            Credentials c = credentials(req);
            session = connect(c, 8000);
            String cmd = "echo @@HOSTNAME; (hostname 2>/dev/null || uname -n); "
                    + "echo @@OS; (grep -E '^PRETTY_NAME=' /etc/os-release 2>/dev/null || (cat /etc/issue 2>/dev/null | head -1)); "
                    + "echo @@CPU; (nproc 2>/dev/null); (grep -m1 'model name' /proc/cpuinfo 2>/dev/null); "
                    + "echo @@MEM; (free -h 2>/dev/null || free -m 2>/dev/null); "
                    + "echo @@DISK; (df -h / 2>/dev/null); "
                    + "echo @@END";
            String raw = exec(session, cmd, 20000);
            Map<String, String> sections = sections(raw);

            String hostname = firstLine(sections.get("HOSTNAME"));
            String os = parseOs(sections.get("OS"));
            String cpu = parseCpu(sections.get("CPU"));
            String mem = parseMem(sections.get("MEM"));
            String disk = parseDisk(sections.get("DISK"));

            out.put("success", true);
            out.put("message", "连接成功，已探测服务器信息");
            out.put("hostname", hostname);
            out.put("os", os);
            out.put("cpu", cpu);
            out.put("memory", mem);
            out.put("disk", disk);
            out.put("elapsedMs", System.currentTimeMillis() - start);
        } catch (Exception e) {
            out.put("success", false);
            out.put("message", "SSH 探测失败: " + message(e));
            out.put("elapsedMs", System.currentTimeMillis() - start);
            log.warn("SSH 探测失败 {}: {}", safeTarget(req), e.getMessage());
        } finally {
            disconnect(session);
        }
        return out;
    }

    // ==================== 内部 ====================

    private static class Credentials {
        String host; int port; String user; String password; String sshKey;
    }

    private Credentials credentials(Map<String, Object> req) {
        Credentials c = new Credentials();
        Long id = longVal(req.get("id"));
        c.host = str(req.get("ip"));
        c.port = intVal(req.get("port"), 0);
        c.user = str(req.get("user"));
        c.password = str(req.get("password"));
        c.sshKey = str(req.get("sshKey"));

        if (id != null) {
            ServerAsset s = serverService.getById(id);
            if (c.host == null || c.host.trim().isEmpty()) c.host = s.getIp();
            if (c.port <= 0) c.port = s.getSshPort() == null ? 22 : s.getSshPort();
            if (c.user == null || c.user.trim().isEmpty()) c.user = s.getSshUser() == null ? "root" : s.getSshUser();
            if ((c.password == null || c.password.isEmpty()) && (c.sshKey == null || c.sshKey.isEmpty())) {
                String stored = serverService.decryptPassword(s);
                if (stored != null) c.password = stored;
                if (s.getSshKey() != null && !s.getSshKey().trim().isEmpty()) c.sshKey = s.getSshKey();
            }
        }
        if (c.host == null || c.host.trim().isEmpty()) throw new RuntimeException("主机地址不能为空");
        if (c.user == null || c.user.trim().isEmpty()) c.user = "root";
        if (c.port <= 0) c.port = 22;
        if ((c.password == null || c.password.isEmpty()) && (c.sshKey == null || c.sshKey.isEmpty())) {
            throw new RuntimeException("请填写 SSH 密码或私钥");
        }
        return c;
    }

    private Session connect(Credentials c, int timeout) throws Exception {
        JSch jsch = new JSch();
        Session session;
        if (c.sshKey != null && !c.sshKey.trim().isEmpty()) {
            jsch.addIdentity("ssh-key", c.sshKey.getBytes(StandardCharsets.UTF_8), null, null);
            session = jsch.getSession(c.user, c.host, c.port);
        } else {
            session = jsch.getSession(c.user, c.host, c.port);
            session.setPassword(c.password);
        }
        Properties config = new Properties();
        config.put("StrictHostKeyChecking", "no");
        config.put("PreferredAuthentications",
                c.sshKey != null && !c.sshKey.trim().isEmpty()
                        ? "publickey,password,keyboard-interactive"
                        : "password,keyboard-interactive,publickey");
        session.setConfig(config);
        session.connect(timeout);
        return session;
    }

    private String exec(Session session, String command, int channelTimeout) throws Exception {
        ChannelExec channel = null;
        try {
            channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(command);
            channel.setInputStream(null);
            InputStream in = channel.getInputStream();
            channel.connect(channelTimeout);
            StringBuilder out = new StringBuilder();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line).append('\n');
            }
            int waited = 0;
            while (!channel.isClosed() && waited < 200) {
                Thread.sleep(100);
                waited++;
            }
            return out.toString();
        } finally {
            if (channel != null && channel.isConnected()) channel.disconnect();
        }
    }

    private Map<String, String> sections(String raw) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        String current = null;
        StringBuilder sb = new StringBuilder();
        for (String line : raw.split("\n")) {
            String t = line.trim();
            if (t.startsWith("@@")) {
                if (current != null) map.put(current, sb.toString());
                current = t.substring(2);
                sb.setLength(0);
            } else if (current != null) {
                sb.append(line).append('\n');
            }
        }
        if (current != null) map.put(current, sb.toString());
        return map;
    }

    private String firstLine(String s) {
        if (s == null) return null;
        for (String line : s.split("\n")) if (!line.trim().isEmpty()) return line.trim();
        return null;
    }

    private String parseOs(String s) {
        if (s == null) return null;
        String line = firstLine(s);
        if (line == null) return null;
        if (line.startsWith("PRETTY_NAME=")) {
            line = line.substring("PRETTY_NAME=".length()).trim();
            if (line.startsWith("\"") && line.endsWith("\"") && line.length() > 1) {
                line = line.substring(1, line.length() - 1);
            }
        }
        return line.replace("\\l", "").replace("\\n", "").trim();
    }

    private String parseCpu(String s) {
        if (s == null) return null;
        String cores = null;
        String model = null;
        for (String line : s.split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            if (cores == null && t.matches("\\d+")) cores = t;
            else if (model == null && t.toLowerCase().contains("model name")) {
                int idx = t.indexOf(':');
                if (idx >= 0) model = t.substring(idx + 1).trim();
            }
        }
        if (model != null && cores != null) return model + " / " + cores + " 核";
        if (model != null) return model;
        if (cores != null) return cores + " 核";
        return null;
    }

    private String parseMem(String s) {
        if (s == null) return null;
        for (String line : s.split("\n")) {
            String t = line.trim();
            if (t.startsWith("Mem:")) {
                String[] parts = t.split("\\s+");
                if (parts.length >= 2) return parts[1];
            }
        }
        return firstLine(s);
    }

    private String parseDisk(String s) {
        if (s == null) return null;
        String[] lines = s.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String t = lines[i].trim();
            if (t.isEmpty()) continue;
            String[] p = t.split("\\s+");
            if (p.length >= 6 && p[1].matches("[0-9.]+[KMGTP]?i?")) {
                return p[1] + "（已用 " + p[4] + "）";
            }
        }
        return firstLine(s);
    }

    private String message(Exception e) {
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m;
    }

    private String safeTarget(Map<String, Object> req) {
        Object user = req.get("user");
        Object ip = req.get("ip");
        Object id = req.get("id");
        return (user == null ? "" : user) + "@" + (ip == null ? ("id=" + id) : ip);
    }

    private String str(Object o) { return o == null ? null : o.toString(); }

    private Long longVal(Object o) {
        try { return o == null ? null : Long.valueOf(o.toString()); } catch (Exception e) { return null; }
    }

    private int intVal(Object o, int def) {
        try { return o == null ? def : Integer.parseInt(o.toString()); } catch (Exception e) { return def; }
    }

    private void disconnect(Session session) {
        try { if (session != null && session.isConnected()) session.disconnect(); } catch (Exception ignore) {}
    }
}