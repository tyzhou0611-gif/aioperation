package com.aiops.service;

import com.aiops.common.ServiceException;
import com.aiops.entity.MiddlewareAsset;
import com.aiops.repository.MiddlewareAssetRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 中间件实例监控：为每个中间件资产提供独立的实时数据（详情页使用）。
 *
 * 数据获取方式（均为只读）：
 * - 通用：对“节点列表”中的 host:port 做 TCP 连通性探测
 * - ZooKeeper：四字命令 ruok/srvr/mntr/conf（需服务端开启对应白名单）
 * - Redis：原生 RESP 协议 PING/INFO/CLUSTER INFO/CLUSTER NODES（复用资产中的访问账号/密码）
 * - Hadoop：通过 ResourceManager REST API（consoleUrl 或 http://ip:8088）
 * - Kafka：broker 节点 TCP 连通性（节点列表来自资产配置）
 */
@Service
public class MiddlewareMonitorService {

    private static final Logger log = LoggerFactory.getLogger(MiddlewareMonitorService.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int MAX_NODES = 50;
    private static final int PROBE_TIMEOUT_MS = 1200;
    private static final Pattern ZK_SERVER = Pattern.compile("server\\.(\\d+)\\s*=\\s*([^:\\s]+):(\\d+):(\\d+)(?::([a-zA-Z]+))?");

    private final MiddlewareAssetRepository repository;
    private final PasswordEncryptor passwordEncryptor;
    private final RestTemplate restTemplate;

    public MiddlewareMonitorService(MiddlewareAssetRepository repository, PasswordEncryptor passwordEncryptor) {
        this.repository = repository;
        this.passwordEncryptor = passwordEncryptor;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(3000);
        f.setReadTimeout(8000);
        this.restTemplate = new RestTemplate(f);
    }

    public Map<String, Object> monitor(Long id) {
        MiddlewareAsset asset = repository.findById(id)
                .orElseThrow(() -> new ServiceException("中间件不存在: " + id));
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("type", asset.getType());
        out.put("timestamp", LocalDateTime.now().toString());
        out.put("consoleUrl", firstNonBlank(asset.getConsoleUrl(), defaultConsole(asset)));

        List<Map<String, Object>> nodes = probeNodes(asset);
        out.put("nodes", nodes);
        out.put("reachableNodes", nodes.stream().filter(n -> Boolean.TRUE.equals(n.get("reachable"))).count());
        out.put("totalNodes", nodes.size());

        String type = asset.getType() == null ? "" : asset.getType().toUpperCase(Locale.ROOT);
        try {
            if ("ZOOKEEPER".equals(type)) out.put("zookeeper", zookeeper(asset, nodes));
            else if ("REDIS".equals(type)) out.put("redis", redis(asset, nodes));
            else if ("HADOOP".equals(type)) out.put("yarn", yarn(asset));
            else if ("KAFKA".equals(type)) out.put("kafka", kafka(asset, nodes));
        } catch (Exception e) {
            log.warn("中间件监控失败 id={} type={}: {}", id, type, e.getMessage());
            out.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        return out;
    }

    // ==================== 列表页端口存活探测 ====================

    /**
     * 列表页/资产状态探测：只做 TCP 端口探测。
     * 优先探测资产主 IP:port，再补充节点列表（最多 5 个目标）；
     * 任意目标可达 → ONLINE，否则 → OFFLINE。永远不返回 UNKNOWN。
     */
    public Map<String, Object> probeStatus(MiddlewareAsset asset) {
        List<String[]> targets = statusTargets(asset);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("id", asset.getId());
        out.put("name", asset.getName());
        out.put("type", asset.getType());
        out.put("ip", asset.getIp());
        out.put("port", asset.getPort());
        if (targets.isEmpty()) {
            out.put("status", "OFFLINE");
            out.put("reachable", 0);
            out.put("total", 0);
            out.put("latencyMs", null);
            out.put("targets", new ArrayList<Map<String, Object>>());
            out.put("message", "未配置 IP/端口，状态按离线处理");
            return out;
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(5, targets.size()));
        try {
            List<Future<Map<String, Object>>> futures = new ArrayList<Future<Map<String, Object>>>();
            for (final String[] target : targets) {
                futures.add(pool.submit(() -> probe(target[0], Integer.parseInt(target[1]))));
            }
            List<Map<String, Object>> results = new ArrayList<Map<String, Object>>();
            int reachable = 0;
            Long bestLatency = null;
            for (Future<Map<String, Object>> f : futures) {
                Map<String, Object> r;
                try {
                    r = f.get(3, TimeUnit.SECONDS);
                } catch (Exception e) {
                    continue;
                }
                results.add(r);
                if (Boolean.TRUE.equals(r.get("reachable"))) {
                    reachable++;
                    Object l = r.get("latencyMs");
                    if (l instanceof Number) {
                        long ms = ((Number) l).longValue();
                        if (bestLatency == null || ms < bestLatency) bestLatency = ms;
                    }
                }
            }
            out.put("status", reachable > 0 ? "ONLINE" : "OFFLINE");
            out.put("reachable", reachable);
            out.put("total", results.size());
            out.put("latencyMs", bestLatency);
            out.put("targets", results);
            out.put("message", reachable > 0 ? "端口存活" : "端口不可达");
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    /** 探测并持久化状态（不会写入 UNKNOWN） */
    public Map<String, Object> probeAndSave(MiddlewareAsset asset) {
        Map<String, Object> out = probeStatus(asset);
        asset.setStatus(String.valueOf(out.get("status")));
        repository.save(asset);
        return out;
    }

    /** 批量探测并持久化（用于列表自动探测/探测本页） */
    public List<Map<String, Object>> probeAndSaveAll(List<MiddlewareAsset> assets) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        if (assets == null || assets.isEmpty()) return out;
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(8, assets.size()));
        try {
            List<Future<Map<String, Object>>> futures = new ArrayList<Future<Map<String, Object>>>();
            for (final MiddlewareAsset a : assets) {
                futures.add(pool.submit(() -> probeAndSave(a)));
            }
            for (Future<Map<String, Object>> f : futures) {
                try {
                    out.add(f.get(6, TimeUnit.SECONDS));
                } catch (Exception e) {
                    log.debug("批量状态探测超时: {}", e.getMessage());
                }
            }
        } finally {
            pool.shutdownNow();
        }
        out.sort((a, b) -> Long.compare(num(a.get("id")), num(b.get("id"))));
        return out;
    }

    private long num(Object o) {
        return o instanceof Number ? ((Number) o).longValue() : 0L;
    }

    /** 状态探测目标：主 IP:port + 节点列表（去重，最多 5 个，避免一次探测过慢） */
    private List<String[]> statusTargets(MiddlewareAsset asset) {
        List<String[]> out = new ArrayList<String[]>();
        if (!blank(asset.getIp())) {
            int p = asset.getPort() != null && asset.getPort() > 0 ? asset.getPort() : defaultPort(asset.getType());
            if (p > 0) out.add(new String[]{asset.getIp().trim(), String.valueOf(p)});
        }
        for (String[] t : parseTargets(asset)) {
            if (out.size() >= 5) break;
            boolean dup = false;
            for (String[] o : out) {
                if (o[0].equalsIgnoreCase(t[0]) && o[1].equals(t[1])) { dup = true; break; }
            }
            if (!dup) out.add(t);
        }
        return out;
    }

    // ==================== 节点连通性 ====================

    private List<Map<String, Object>> probeNodes(MiddlewareAsset asset) {
        List<String[]> targets = parseTargets(asset);
        if (targets.isEmpty()) return new ArrayList<Map<String, Object>>();
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(8, targets.size()));
        try {
            List<Future<Map<String, Object>>> futures = new ArrayList<Future<Map<String, Object>>>();
            for (final String[] target : targets) {
                futures.add(pool.submit(() -> probe(target[0], Integer.parseInt(target[1]))));
            }
            List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
            for (Future<Map<String, Object>> f : futures) {
                try {
                    out.add(f.get(3, TimeUnit.SECONDS));
                } catch (Exception e) {
                    log.debug("节点探测超时: {}", e.getMessage());
                }
            }
            out.sort((a, b) -> String.valueOf(a.get("target")).compareTo(String.valueOf(b.get("target"))));
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    private List<String[]> parseTargets(MiddlewareAsset asset) {
        Set<String> raw = new LinkedHashSet<String>();
        if (asset.getNodeList() != null) {
            for (String s : asset.getNodeList().split("[,;\\s]+")) {
                if (!s.trim().isEmpty()) raw.add(s.trim());
            }
        }
        if (raw.isEmpty() && !blank(asset.getIp())) {
            raw.add(asset.getIp().trim() + ":" + (asset.getPort() != null && asset.getPort() > 0
                    ? asset.getPort() : defaultPort(asset.getType())));
        }
        List<String[]> targets = new ArrayList<String[]>();
        for (String s : raw) {
            if (targets.size() >= MAX_NODES) break;
            String v = s;
            int scheme = v.indexOf("://");
            if (scheme >= 0) v = v.substring(scheme + 3);
            int slash = v.indexOf('/');
            if (slash > 0) v = v.substring(0, slash);
            String host = v;
            int port = asset.getPort() != null && asset.getPort() > 0 ? asset.getPort() : defaultPort(asset.getType());
            int idx = v.lastIndexOf(':');
            if (idx > 0) {
                String p = v.substring(idx + 1);
                if (p.matches("\\d{1,5}")) {
                    host = v.substring(0, idx);
                    port = Integer.parseInt(p);
                }
            }
            if (!host.trim().isEmpty() && port > 0) targets.add(new String[]{host.trim(), String.valueOf(port)});
        }
        return targets;
    }

    private Map<String, Object> probe(String host, int port) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("target", host + ":" + port);
        m.put("host", host);
        m.put("port", port);
        long start = System.currentTimeMillis();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), PROBE_TIMEOUT_MS);
            m.put("reachable", true);
            m.put("latencyMs", System.currentTimeMillis() - start);
        } catch (Exception e) {
            m.put("reachable", false);
            m.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        return m;
    }

    // ==================== ZooKeeper ====================

    private Map<String, Object> zookeeper(MiddlewareAsset asset, List<Map<String, Object>> nodes) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        String host = blank(asset.getIp()) ? "127.0.0.1" : asset.getIp().trim();
        int port = asset.getPort() != null && asset.getPort() > 0 ? asset.getPort() : 2181;
        out.put("host", host);
        out.put("port", port);

        Map<String, String> raw = fourLetterAll(host, port);

        Map<String, String> mntr = parseKv(raw.get("mntr"), "\t");
        Map<String, String> srvr = parseKv(raw.get("srvr"), ":\\s*");

        boolean reachable = "imok".equalsIgnoreCase(raw.get("ruok"))
                || !mntr.isEmpty() || !srvr.isEmpty();
        out.put("reachable", reachable);
        out.put("mode", firstNonBlank(mntr.get("zk_server_state"), srvr.get("Mode")));
        out.put("version", mntr.get("zk_version"));
        out.put("connections", firstNonBlank(mntr.get("zk_num_alive_connections"), srvr.get("Connections")));
        String avgLatency = mntr.get("zk_avg_latency");
        String maxLatency = mntr.get("zk_max_latency");
        String latency = srvr.get("Latency min/avg/max");
        if (!blank(latency)) {
            String[] parts = latency.split("/");
            if (parts.length >= 3) {
                if (blank(avgLatency)) avgLatency = parts[1];
                if (blank(maxLatency)) maxLatency = parts[2];
            }
        }
        out.put("avgLatency", avgLatency);
        out.put("maxLatency", maxLatency);
        out.put("outstanding", firstNonBlank(mntr.get("zk_outstanding_requests"), srvr.get("Outstanding")));
        out.put("znodeCount", firstNonBlank(mntr.get("zk_znode_count"), srvr.get("Node count")));
        out.put("ephemerals", mntr.get("zk_ephemerals_count"));
        out.put("watches", mntr.get("zk_watch_count"));
        out.put("followers", mntr.get("zk_followers"));
        out.put("syncedFollowers", mntr.get("zk_synced_followers"));
        out.put("uptimeMs", mntr.get("zk_uptime"));

        List<Map<String, Object>> peers = new ArrayList<Map<String, Object>>();
        String conf = raw.get("conf");
        if (conf != null && !conf.contains("not in the whitelist")) {
            Matcher m = ZK_SERVER.matcher(conf);
            while (m.find()) {
                Map<String, Object> p = new LinkedHashMap<String, Object>();
                p.put("id", m.group(1));
                p.put("host", m.group(2));
                p.put("port", Integer.parseInt(m.group(3)));
                p.put("role", m.group(5) == null ? "participant" : m.group(5));
                peers.add(p);
            }
        }
        if (peers.isEmpty()) {
            for (Map<String, Object> n : nodes) {
                Map<String, Object> p = new LinkedHashMap<String, Object>();
                p.put("id", "-");
                p.put("host", n.get("host"));
                p.put("port", n.get("port"));
                p.put("role", "configured");
                p.put("reachable", n.get("reachable"));
                p.put("latencyMs", n.get("latencyMs"));
                p.put("error", n.get("error"));
                peers.add(p);
            }
        } else {
            probePeers(peers);
        }
        out.put("peers", peers);
        String err = errorOf(raw.get("ruok"), raw.get("srvr"));
        if (err != null) out.put("error", err);
        return out;
    }

    /** 并行执行四字命令，避免单个不可用命令拖慢整个页面 */
    private Map<String, String> fourLetterAll(String host, int port) {
        final String[] commands = {"ruok", "srvr", "mntr", "conf"};
        Map<String, String> out = new LinkedHashMap<String, String>();
        ExecutorService pool = Executors.newFixedThreadPool(commands.length);
        try {
            Map<String, Future<String>> futures = new LinkedHashMap<String, Future<String>>();
            for (final String cmd : commands) {
                futures.put(cmd, pool.submit(() -> fourLetter(host, port, cmd)));
            }
            for (Map.Entry<String, Future<String>> e : futures.entrySet()) {
                try {
                    out.put(e.getKey(), e.getValue().get(3, TimeUnit.SECONDS));
                } catch (Exception ex) {
                    out.put(e.getKey(), "ERROR: " + ex.getClass().getSimpleName());
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return out;
    }

    /** 并行探测 ZooKeeper 集群节点 */
    private void probePeers(List<Map<String, Object>> peers) {
        if (peers.isEmpty()) return;
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(8, peers.size()));
        try {
            List<Future<?>> futures = new ArrayList<Future<?>>();
            for (final Map<String, Object> peer : peers) {
                futures.add(pool.submit(() -> {
                    String h = String.valueOf(peer.get("host"));
                    int p = Integer.parseInt(String.valueOf(peer.get("port")));
                    Map<String, Object> r = probe(h, p);
                    peer.put("reachable", r.get("reachable"));
                    peer.put("latencyMs", r.get("latencyMs"));
                    peer.put("error", r.get("error"));
                }));
            }
            for (Future<?> f : futures) {
                try { f.get(3, TimeUnit.SECONDS); } catch (Exception ignore) { }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** 发送 ZooKeeper 四字命令（部分命令需在 4lw.commands.whitelist 中开启） */
    private String fourLetter(String host, int port, String cmd) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), PROBE_TIMEOUT_MS);
            socket.setSoTimeout(1500);
            OutputStream out = socket.getOutputStream();
            out.write(cmd.getBytes(StandardCharsets.UTF_8));
            out.flush();
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            try {
                while ((n = in.read(b)) > 0 && buf.size() < 256 * 1024) buf.write(b, 0, n);
            } catch (SocketTimeoutException ignore) { /* 某些命令不主动断开 */ }
            return new String(buf.toByteArray(), StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return "ERROR: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    // ==================== Redis ====================

    private Map<String, Object> redis(MiddlewareAsset asset, List<Map<String, Object>> nodes) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        String host = blank(asset.getIp()) ? "127.0.0.1" : asset.getIp().trim();
        int port = asset.getPort() != null && asset.getPort() > 0 ? asset.getPort() : 6379;
        out.put("host", host);
        out.put("port", port);
        out.put("reachable", false);

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 2000);
            socket.setSoTimeout(3500);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream os = socket.getOutputStream();

            String pwd = decrypt(asset);
            if (!blank(pwd)) {
                String user = asset.getDbUsername();
                Object auth = blank(user) ? redisCommand(in, os, "AUTH", pwd)
                        : redisCommand(in, os, "AUTH", user.trim(), pwd);
                if (auth instanceof RedisError) {
                    out.put("reachable", true);
                    out.put("authError", ((RedisError) auth).message);
                    return out;
                }
            }

            Object ping = redisCommand(in, os, "PING");
            out.put("reachable", "PONG".equalsIgnoreCase(String.valueOf(ping)));
            Map<String, String> info = parseInfo(String.valueOf(redisCommand(in, os, "INFO")));
            out.put("version", info.get("redis_version"));
            out.put("mode", info.get("redis_mode"));
            out.put("role", info.get("role"));
            out.put("uptimeSeconds", info.get("uptime_in_seconds"));
            out.put("connectedClients", info.get("connected_clients"));
            out.put("usedMemory", info.get("used_memory_human"));
            out.put("totalConnections", info.get("total_connections_received"));
            List<String> keyspaces = new ArrayList<String>();
            for (Map.Entry<String, String> e : info.entrySet()) {
                if (e.getKey().startsWith("db")) keyspaces.add(e.getKey() + " · " + e.getValue());
            }
            out.put("keyspaces", keyspaces);

            Object clusterInfoRaw = redisCommand(in, os, "CLUSTER", "INFO");
            Object clusterNodesRaw = redisCommand(in, os, "CLUSTER", "NODES");
            boolean cluster = clusterInfoRaw instanceof String && ((String) clusterInfoRaw).contains("cluster_state");
            Map<String, String> clusterInfo = parseInfo(String.valueOf(clusterInfoRaw));
            out.put("clusterEnabled", cluster);
            if (cluster) {
                out.put("clusterState", clusterInfo.get("cluster_state"));
                out.put("clusterKnownNodes", clusterInfo.get("cluster_known_nodes"));
                out.put("clusterSize", clusterInfo.get("cluster_size"));
                out.put("slotsAssigned", clusterInfo.get("cluster_slots_assigned"));
            }

            List<Map<String, Object>> redisNodes = new ArrayList<Map<String, Object>>();
            if (clusterNodesRaw instanceof String) {
                redisNodes = parseClusterNodes((String) clusterNodesRaw);
            }
            if (redisNodes.isEmpty()) {
                redisNodes = standaloneNodes(host, port, info);
            }
            int masters = 0, slaves = 0;
            for (Map<String, Object> n : redisNodes) {
                if ("master".equals(n.get("role"))) masters++; else slaves++;
            }
            out.put("nodes", redisNodes);
            out.put("masterCount", masters);
            out.put("slaveCount", slaves);
            out.put("slotAssigned", clusterInfo.get("cluster_slots_assigned"));
        } catch (Exception e) {
            out.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        return out;
    }

    private Object redisCommand(InputStream in, OutputStream out, String... args) throws IOException {
        StringBuilder sb = new StringBuilder("*").append(args.length).append("\r\n");
        for (String a : args) {
            byte[] bytes = a.getBytes(StandardCharsets.UTF_8);
            sb.append("$").append(bytes.length).append("\r\n").append(a).append("\r\n");
        }
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
        return readResp(in);
    }

    private Object readResp(InputStream in) throws IOException {
        int type = in.read();
        if (type < 0) throw new EOFException("Redis 连接已关闭");
        String line = readLine(in);
        switch (type) {
            case '+': return line;
            case '-': return new RedisError(line);
            case ':': return Long.parseLong(line);
            case '$': {
                int len = Integer.parseInt(line);
                if (len < 0) return null;
                byte[] buf = new byte[len];
                int off = 0;
                while (off < len) {
                    int n = in.read(buf, off, len - off);
                    if (n < 0) throw new EOFException("Redis 响应不完整");
                    off += n;
                }
                readLine(in);
                return new String(buf, StandardCharsets.UTF_8);
            }
            case '*': {
                int count = Integer.parseInt(line);
                if (count < 0) return null;
                List<Object> list = new ArrayList<Object>();
                for (int i = 0; i < count; i++) list.add(readResp(in));
                return list;
            }
            default: throw new IOException("未知的 Redis 响应类型: " + (char) type);
        }
    }

    private String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int prev = -1, b;
        while ((b = in.read()) >= 0) {
            if (prev == '\r' && b == '\n') break;
            if (prev >= 0) buf.write(prev);
            prev = b;
        }
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    private List<Map<String, Object>> parseClusterNodes(String raw) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (String line : raw.split("\\r?\\n")) {
            String[] f = line.trim().split("\\s+");
            if (f.length < 8) continue;
            Map<String, Object> n = new LinkedHashMap<String, Object>();
            n.put("id", f[0]);
            String addr = f[1];
            int at = addr.indexOf('@');
            if (at > 0) addr = addr.substring(0, at);
            int idx = addr.lastIndexOf(':');
            String host = idx > 0 ? addr.substring(0, idx) : addr;
            String port = idx > 0 ? addr.substring(idx + 1) : "";
            n.put("address", addr);
            n.put("host", host);
            n.put("port", port);
            String flags = f[2];
            n.put("role", flags.contains("master") ? "master" : flags.contains("slave") ? "slave" : "unknown");
            n.put("myself", flags.contains("myself"));
            n.put("masterId", "-".equals(f[3]) ? null : f[3]);
            n.put("linkState", f[7]);
            StringBuilder slots = new StringBuilder();
            int slotCount = 0;
            for (int i = 8; i < f.length; i++) {
                if (f[i].startsWith("[")) continue;
                if (slots.length() > 0) slots.append(",");
                slots.append(f[i]);
                slotCount += countSlots(f[i]);
            }
            n.put("slots", slots.toString());
            n.put("slotCount", slotCount);
            n.put("reachable", "connected".equalsIgnoreCase(f[7]));
            out.add(n);
        }
        return out;
    }

    private int countSlots(String token) {
        try {
            int dash = token.indexOf('-');
            if (dash > 0) {
                int a = Integer.parseInt(token.substring(0, dash));
                int b = Integer.parseInt(token.substring(dash + 1));
                return Math.max(0, b - a + 1);
            }
            Integer.parseInt(token);
            return 1;
        } catch (Exception e) {
            return 0;
        }
    }

    private List<Map<String, Object>> standaloneNodes(String host, int port, Map<String, String> info) {
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        Map<String, Object> self = new LinkedHashMap<String, Object>();
        self.put("id", "self");
        self.put("address", host + ":" + port);
        self.put("host", host);
        self.put("port", String.valueOf(port));
        self.put("role", "master".equals(info.get("role")) ? "master" : "slave");
        self.put("linkState", "connected");
        self.put("myself", true);
        self.put("slotCount", 0);
        list.add(self);
        for (Map.Entry<String, String> e : info.entrySet()) {
            if (!e.getKey().startsWith("slave")) continue;
            Map<String, String> kv = parseKv(e.getValue(), ",");
            Map<String, Object> n = new LinkedHashMap<String, Object>();
            n.put("id", e.getKey());
            n.put("address", kv.get("ip") + ":" + kv.get("port"));
            n.put("host", kv.get("ip"));
            n.put("port", kv.get("port"));
            n.put("role", "slave");
            n.put("linkState", kv.get("state"));
            n.put("myself", false);
            n.put("slotCount", 0);
            list.add(n);
        }
        return list;
    }

    // ==================== Hadoop / YARN ====================

    private Map<String, Object> yarn(MiddlewareAsset asset) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        String base = normalizeHttp(asset.getConsoleUrl());
        if (blank(base)) {
            base = "http://" + (blank(asset.getIp()) ? "127.0.0.1" : asset.getIp().trim())
                    + ":" + (asset.getPort() != null && asset.getPort() > 0 ? asset.getPort() : 8088);
        }
        out.put("baseUrl", base);
        Map<String, String> errors = new LinkedHashMap<String, String>();

        JsonNode info = getJson(base + "/ws/v1/cluster/info", errors, "clusterInfo");
        if (info != null) out.put("cluster", info.path("clusterInfo"));

        JsonNode metrics = getJson(base + "/ws/v1/cluster/metrics", errors, "metrics");
        if (metrics != null) out.put("metrics", metrics.path("clusterMetrics"));

        JsonNode nodes = getJson(base + "/ws/v1/cluster/nodes", errors, "nodes");
        List<Map<String, Object>> nodeList = new ArrayList<Map<String, Object>>();
        if (nodes != null) {
            for (JsonNode n : nodes.path("nodes").path("node")) {
                if (nodeList.size() >= 100) break;
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("id", n.path("id").asText(""));
                m.put("host", n.path("nodeHostName").asText(""));
                m.put("rack", n.path("rack").asText(""));
                m.put("state", n.path("state").asText(""));
                m.put("containers", n.path("numContainers").asInt(0));
                m.put("usedMemoryMB", n.path("usedMemoryMB").asLong(0));
                m.put("availableMemoryMB", n.path("availMemoryMB").asLong(0));
                nodeList.add(m);
            }
        }
        out.put("nodes", nodeList);

        JsonNode apps = getJson(base + "/ws/v1/cluster/apps?states=RUNNING,ACCEPTED,NEW", errors, "apps");
        List<Map<String, Object>> appList = new ArrayList<Map<String, Object>>();
        if (apps != null) {
            for (JsonNode a : apps.path("apps").path("app")) {
                if (appList.size() >= 100) break;
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("id", a.path("id").asText(""));
                m.put("name", a.path("name").asText(""));
                m.put("user", a.path("user").asText(""));
                m.put("queue", a.path("queue").asText(""));
                m.put("type", a.path("applicationType").asText(a.path("type").asText("")));
                m.put("state", a.path("state").asText(""));
                m.put("progress", a.path("progress").asDouble(0));
                m.put("startedTime", a.path("startedTime").asLong(0));
                appList.add(m);
            }
        }
        out.put("apps", appList);
        out.put("errors", errors);
        return out;
    }

    private JsonNode getJson(String url, Map<String, String> errors, String key) {
        try {
            ResponseEntity<String> resp = restTemplate.getForEntity(URI.create(url), String.class);
            return mapper.readTree(resp.getBody());
        } catch (Exception e) {
            errors.put(key, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return null;
        }
    }

    // ==================== Kafka ====================

    private Map<String, Object> kafka(MiddlewareAsset asset, List<Map<String, Object>> nodes) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("brokers", nodes);
        out.put("consoleUrl", firstNonBlank(asset.getConsoleUrl(), null));
        out.put("note", "Broker 列表来自资产配置；页面通过 TCP 探测连接状态。如需 Topic/消费组详情，可填写 Kafka 管理控制台地址。");
        return out;
    }

    // ==================== 工具 ====================

    private int defaultPort(String type) {
        if (type == null) return 0;
        switch (type.toUpperCase(Locale.ROOT)) {
            case "KAFKA": return 9092;
            case "ZOOKEEPER": return 2181;
            case "REDIS": return 6379;
            case "HADOOP": return 8088;
            case "NACOS": return 8848;
            case "RABBITMQ": return 5672;
            default: return 0;
        }
    }

    private String defaultConsole(MiddlewareAsset asset) {
        if ("HADOOP".equalsIgnoreCase(asset.getType()) && !blank(asset.getIp())) {
            return "http://" + asset.getIp().trim() + ":8088";
        }
        return null;
    }

    private String normalizeHttp(String url) {
        if (url == null) return null;
        String v = url.trim();
        if (v.isEmpty()) return null;
        if (!v.startsWith("http://") && !v.startsWith("https://")) v = "http://" + v;
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        return v;
    }

    private String decrypt(MiddlewareAsset asset) {
        String enc = asset.getDbPasswordEncrypted();
        if (enc == null || enc.isEmpty()) return null;
        try {
            return passwordEncryptor.decrypt(enc);
        } catch (Exception e) {
            throw new ServiceException("访问密码解密失败，请重新设置（AES_KEY 可能已变更）");
        }
    }

    private Map<String, String> parseInfo(String text) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        if (text == null || text.startsWith("ERROR:") || text.startsWith("-")) return map;
        for (String line : text.split("\\r?\\n")) {
            String v = line.trim();
            if (v.isEmpty() || v.startsWith("#")) continue;
            int idx = v.indexOf(':');
            if (idx > 0) map.put(v.substring(0, idx).trim(), v.substring(idx + 1).trim());
        }
        return map;
    }

    private Map<String, String> parseKv(String text, String separator) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        if (text == null || text.startsWith("ERROR:") || text.contains("not in the whitelist")) return map;
        for (String line : text.split("\\r?\\n")) {
            String[] parts = line.trim().split(separator, 2);
            if (parts.length == 2) map.put(parts[0].trim(), parts[1].trim());
        }
        return map;
    }

    private String errorOf(String... values) {
        for (String v : values) {
            if (v != null && v.startsWith("ERROR:")) return v.substring(6).trim();
        }
        return null;
    }

    private String firstNonBlank(String a, String b) {
        return !blank(a) ? a : (!blank(b) ? b : null);
    }

    private boolean blank(String s) { return s == null || s.trim().isEmpty(); }

    private static class RedisError {
        final String message;
        RedisError(String message) { this.message = message; }
    }
}