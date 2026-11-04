package com.aiops.service;

import com.aiops.entity.ServerAsset;
import com.aiops.infrastructure.metrics.PrometheusMetricsProvider;
import com.aiops.repository.ServerAssetRepository;
import com.jcraft.jsch.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PreDestroy;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * 服务器状态探测，支持四种来源：
 * <ul>
 *   <li><b>auto</b>（推荐）：Prometheus 优先；Prometheus 无数据或离线时用 SSH/TCP/ICMP 复核</li>
 *   <li><b>prometheus</b>：Prometheus up 指标优先，未采集到该主机时自动 SSH/TCP/ICMP 兜底</li>
 *   <li><b>ssh</b>：使用资产中保存的密码/私钥登录探测（无凭据返回 UNKNOWN）</li>
 *   <li><b>icmp</b>：系统 ping</li>
 * </ul>
 * 来源由 sys_config 的 server.status.source 配置，页面修改即时生效。
 * 状态来源会写入 server_asset.status_source，便于页面显示“这条状态是怎么来的”。
 */
@Service
public class ServerStatusService {

    private static final Logger log = LoggerFactory.getLogger(ServerStatusService.class);
    private static final Set<String> SOURCES = new HashSet<String>(
            Arrays.asList("auto", "prometheus", "ssh", "icmp"));

    private static final String ONLINE = "ONLINE";
    private static final String OFFLINE = "OFFLINE";
    private static final String UNKNOWN = "UNKNOWN";

    private final ServerAssetRepository repository;
    private final PrometheusMetricsProvider prometheusProvider;
    private final ServerSshService serverSshService;
    private final ConfigService config;

    /** 异步探测线程池：新建/复制/编辑服务器后立即探测，避免列表长时间显示“未知” */
    private final ExecutorService probeExecutor = Executors.newFixedThreadPool(4, new ThreadFactory() {
        private int seq = 0;
        @Override
        public synchronized Thread newThread(Runnable r) {
            Thread t = new Thread(r, "server-status-probe-" + (++seq));
            t.setDaemon(true);
            return t;
        }
    });

    public ServerStatusService(ServerAssetRepository repository,
                               PrometheusMetricsProvider prometheusProvider,
                               ServerSshService serverSshService,
                               ConfigService config) {
        this.repository = repository;
        this.prometheusProvider = prometheusProvider;
        this.serverSshService = serverSshService;
        this.config = config;
    }

    public String currentSource() {
        String s = config.getString("server.status.source", "auto");
        if (s == null) return "auto";
        s = s.trim().toLowerCase(Locale.ROOT);
        return SOURCES.contains(s) ? s : "auto";
    }

    @Transactional
    public ServerAsset probe(Long id) {
        ServerAsset asset = repository.findById(id)
                .orElseThrow(() -> new com.aiops.common.ServiceException("服务器不存在，ID: " + id));
        String source = currentSource();
        ProbeResult result = probeBySource(asset, source);
        if (result != null && !UNKNOWN.equals(result.status)) {
            asset.setStatus(result.status);
            asset.setStatusSource(result.source);
        }
        asset.setLastProbeTime(LocalDateTime.now());
        repository.save(asset);
        return safe(asset);
    }

    /** 异步探测指定服务器（新建/复制/编辑后调用），不阻塞请求 */
    public void probeAsync(Long id) {
        if (id == null) return;
        try {
            probeExecutor.submit(() -> {
                try {
                    probe(id);
                } catch (Exception e) {
                    log.debug("异步状态探测失败 serverId={}: {}", id, e.getMessage());
                }
            });
        } catch (Exception e) {
            log.warn("提交状态探测任务失败 serverId={}: {}", id, e.getMessage());
        }
    }

    /** 探测全部（可按可见服务器范围过滤），并行执行，避免 SSH/ICMP 串行过慢。 */
    @Transactional
    public List<ServerAsset> probeAll(Set<Long> onlyIds) {
        List<ServerAsset> all = repository.findAll();
        if (onlyIds != null) {
            List<ServerAsset> filtered = new ArrayList<ServerAsset>();
            for (ServerAsset a : all) if (onlyIds.contains(a.getId())) filtered.add(a);
            all = filtered;
        }
        if (all.isEmpty()) return new ArrayList<ServerAsset>();

        final String source = currentSource();
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(16, all.size()));
        List<Future<ProbeResult>> futures = new ArrayList<Future<ProbeResult>>();
        for (final ServerAsset asset : all) {
            futures.add(pool.submit(() -> probeBySource(asset, source)));
        }
        LocalDateTime now = LocalDateTime.now();
        int online = 0;
        for (int i = 0; i < all.size(); i++) {
            ServerAsset asset = all.get(i);
            ProbeResult result = null;
            try {
                result = futures.get(i).get(15, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.debug("状态探测超时/失败 serverId={}: {}", asset.getId(), e.getMessage());
            }
            if (result != null && !UNKNOWN.equals(result.status)) {
                asset.setStatus(result.status);
                asset.setStatusSource(result.source);
            }
            asset.setLastProbeTime(now);
            if (ONLINE.equals(asset.getStatus())) online++;
        }
        pool.shutdownNow();
        List<ServerAsset> saved = repository.saveAll(all);
        List<ServerAsset> out = new ArrayList<ServerAsset>();
        for (ServerAsset a : saved) out.add(safe(a));
        log.info("服务器状态批量探测完成 source={} total={} online={}", source, out.size(), online);
        return out;
    }

    // ==================== 探测来源 ====================

    private ProbeResult probeBySource(ServerAsset asset, String source) {
        try {
            if ("ssh".equals(source)) return probeSsh(asset);
            if ("icmp".equals(source)) return probeIcmp(asset);
            ProbeResult prometheus = probePrometheus(asset);
            if ("auto".equals(source)) return probeAuto(asset, prometheus);
            // prometheus 模式：有明确结果直接采用；Prometheus 未采集到该主机时自动兜底
            if (!UNKNOWN.equals(prometheus.status)) return prometheus;
            return probeFallback(asset, prometheus.source);
        } catch (Exception e) {
            log.debug("状态探测失败 serverId={} source={}: {}", asset.getId(), source, e.getMessage());
            return new ProbeResult(UNKNOWN, source);
        }
    }

    /** 自动：Prometheus 说在线直接返回；说离线/无数据时用 SSH/TCP/ICMP 复核 */
    private ProbeResult probeAuto(ServerAsset asset, ProbeResult prometheus) {
        if (ONLINE.equals(prometheus.status)) return prometheus;
        ProbeResult evidence = probeReachability(asset);
        if (evidence != null && ONLINE.equals(evidence.status)) return evidence;
        if (OFFLINE.equals(prometheus.status)) return prometheus;
        if (evidence != null && !UNKNOWN.equals(evidence.status)) return evidence;
        return new ProbeResult(UNKNOWN, prometheus.source);
    }

    /**
     * Prometheus 无数据时的兜底：优先 SSH（有凭据时），否则 TCP 连接 SSH 端口，最后 ICMP。
     * 任何一步能证明主机可达即返回 ONLINE。
     */
    private ProbeResult probeFallback(ServerAsset asset, String defaultSource) {
        ProbeResult evidence = probeReachability(asset);
        if (evidence != null && !UNKNOWN.equals(evidence.status)) return evidence;
        return new ProbeResult(UNKNOWN, defaultSource);
    }

    private ProbeResult probeReachability(ServerAsset asset) {
        ProbeResult first = hasCredentials(asset) ? probeSsh(asset) : probeTcp(asset);
        if (first != null && ONLINE.equals(first.status)) return first;

        ProbeResult ping = probeIcmp(asset);
        if (ping != null && ONLINE.equals(ping.status)) return ping;

        if (first != null && OFFLINE.equals(first.status)) return first;
        if (ping != null && OFFLINE.equals(ping.status)) return ping;
        if (first != null) return first;
        return new ProbeResult(UNKNOWN, ping == null ? "prometheus" : ping.source);
    }

    /** Prometheus：up{instance=~"ip(:port)?"}；查不到或 Prometheus 不可达均返回 UNKNOWN */
    private ProbeResult probePrometheus(ServerAsset asset) {
        String status = prometheusProvider.getServerStatus(asset.getId());
        return new ProbeResult(status == null ? UNKNOWN : status, "prometheus");
    }

    /**
     * SSH：能完成认证即在线。
     * 认证失败 / 连接被拒绝 / 连接重置说明主机可达（只是凭据或 sshd 状态问题），同样视为在线；
     * 超时 / 无法路由 / 域名解析失败才视为离线。无凭据返回 UNKNOWN。
     */
    private ProbeResult probeSsh(ServerAsset asset) {
        if (blank(asset.getIp()) || !hasCredentials(asset)) return new ProbeResult(UNKNOWN, "ssh");
        Session session = null;
        try {
            session = serverSshService.openSessionForServer(asset.getId(), 3000);
            return new ProbeResult(ONLINE, "ssh");
        } catch (Exception e) {
            String msg = (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())
                    .toLowerCase(Locale.ROOT);
            boolean reachable = msg.contains("auth") || msg.contains("permission denied")
                    || msg.contains("connection refused") || msg.contains("connection reset");
            log.debug("SSH 状态探测失败 serverId={} reachable={}: {}", asset.getId(), reachable, e.getMessage());
            return new ProbeResult(reachable ? ONLINE : OFFLINE, "ssh");
        } finally {
            if (session != null && session.isConnected()) session.disconnect();
        }
    }

    /** TCP：连接资产配置的 SSH 端口，能建连或收到 RST（refused）都说明主机可达 */
    private ProbeResult probeTcp(ServerAsset asset) {
        String ip = asset.getIp() == null ? "" : asset.getIp().trim();
        if (ip.isEmpty() || ip.startsWith("-") || !ip.matches("^[A-Za-z0-9.:_-]+$")) {
            return new ProbeResult(UNKNOWN, "tcp");
        }
        int port = asset.getSshPort() == null ? 22 : asset.getSshPort();
        if (port <= 0 || port > 65535) port = 22;
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(ip, port), 1500);
            return new ProbeResult(ONLINE, "tcp");
        } catch (java.net.ConnectException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
            boolean reachable = msg.contains("refused");
            log.debug("TCP 状态探测 serverId={} reachable={}: {}", asset.getId(), reachable, e.getMessage());
            return new ProbeResult(reachable ? ONLINE : OFFLINE, "tcp");
        } catch (java.net.SocketTimeoutException e) {
            return new ProbeResult(OFFLINE, "tcp");
        } catch (Exception e) {
            log.debug("TCP 状态探测失败 serverId={}: {}", asset.getId(), e.getMessage());
            return new ProbeResult(OFFLINE, "tcp");
        } finally {
            try { socket.close(); } catch (Exception ignore) { }
        }
    }

    /** ICMP：调用系统 ping，需服务器 ICMP 可达且本机允许执行 ping */
    private ProbeResult probeIcmp(ServerAsset asset) {
        String target = asset.getIp() == null ? "" : asset.getIp().trim();
        if (target.isEmpty() || target.startsWith("-") || !target.matches("^[A-Za-z0-9.:_-]+$")) {
            return new ProbeResult(UNKNOWN, "icmp");
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        ProcessBuilder pb = windows
                ? new ProcessBuilder("ping", "-n", "1", "-w", "1500", target)
                : new ProcessBuilder("ping", "-c", "1", "-W", "2", target);
        pb.redirectErrorStream(true);
        Process p = null;
        try {
            p = pb.start();
            boolean finished = p.waitFor(2500, TimeUnit.MILLISECONDS);
            if (!finished) {
                p.destroyForcibly();
                return new ProbeResult(OFFLINE, "icmp");
            }
            String text = readAll(p.getInputStream());
            boolean reply = text != null && text.toLowerCase(Locale.ROOT).contains("ttl");
            return new ProbeResult(p.exitValue() == 0 && reply ? ONLINE : OFFLINE, "icmp");
        } catch (Exception e) {
            log.debug("ICMP 状态探测失败 serverId={}: {}", asset.getId(), e.getMessage());
            return new ProbeResult(UNKNOWN, "icmp");
        } finally {
            if (p != null && p.isAlive()) p.destroyForcibly();
        }
    }

    @PreDestroy
    public void shutdown() {
        probeExecutor.shutdownNow();
    }

    // ==================== 工具 ====================

    private boolean hasCredentials(ServerAsset asset) {
        if (asset == null) return false;
        boolean hasPassword = asset.getSshPasswordEncrypted() != null && !asset.getSshPasswordEncrypted().isEmpty();
        boolean hasKey = asset.getSshKey() != null && !asset.getSshKey().trim().isEmpty();
        return hasPassword || hasKey;
    }

    private String readAll(InputStream in) {
        try {
            byte[] buf = new byte[1024];
            StringBuilder sb = new StringBuilder();
            int n;
            while ((n = in.read(buf)) >= 0) sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            return sb.toString();
        } catch (Exception e) {
            return "";
        } finally {
            try { in.close(); } catch (Exception ignore) { }
        }
    }

    private boolean blank(String s) { return s == null || s.trim().isEmpty(); }

    private ServerAsset safe(ServerAsset src) {
        ServerAsset a = new ServerAsset();
        a.setId(src.getId());
        a.setIp(src.getIp());
        a.setHostname(src.getHostname());
        a.setOs(src.getOs());
        a.setCpu(src.getCpu());
        a.setMemory(src.getMemory());
        a.setDisk(src.getDisk());
        a.setStatus(src.getStatus());
        a.setStatusSource(src.getStatusSource());
        a.setLastProbeTime(src.getLastProbeTime());
        a.setEnvironment(src.getEnvironment());
        a.setSshPort(src.getSshPort());
        a.setSshUser(src.getSshUser());
        a.setTags(src.getTags());
        a.setSshPasswordSet(src.getSshPasswordEncrypted() != null && !src.getSshPasswordEncrypted().isEmpty());
        a.setCreateTime(src.getCreateTime());
        a.setUpdateTime(src.getUpdateTime());
        return a;
    }

    private static final class ProbeResult {
        final String status;
        final String source;

        ProbeResult(String status, String source) {
            this.status = status;
            this.source = source;
        }
    }
}