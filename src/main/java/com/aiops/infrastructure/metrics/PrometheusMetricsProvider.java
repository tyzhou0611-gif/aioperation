package com.aiops.infrastructure.metrics;

import com.aiops.entity.ServerAsset;
import com.aiops.repository.ServerAssetRepository;
import com.aiops.service.ConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.URLEncoder;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Prometheus 指标提供者（真实数据源）
 *
 * 由 {@link DynamicMetricsProvider} 按运行时配置选择；Prometheus 地址每次请求从
 * sys_config 读取（页面可改，即时生效），查不到数据时返回 -1 / 空趋势，不再伪造模拟值。
 */
@Component
public class PrometheusMetricsProvider implements MetricsProvider {

    private static final Logger log = LoggerFactory.getLogger(PrometheusMetricsProvider.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ServerAssetRepository serverRepository;
    private final ConfigService config;
    private final String defaultUrl;

    public PrometheusMetricsProvider(
            @Value("${app.prometheus.url:}") String prometheusUrl,
            ServerAssetRepository serverRepository,
            ConfigService config) {
        this.defaultUrl = normalize(prometheusUrl);
        this.serverRepository = serverRepository;
        this.config = config;
        // 设置超时，避免 Prometheus 不可达时状态探测/指标查询长时间挂起
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(3000);
        f.setReadTimeout(8000);
        this.restTemplate = new RestTemplate(f);
        log.info("PrometheusMetricsProvider 已初始化，默认地址: {}", defaultUrl.isEmpty() ? "(未配置，可在系统设置中配置)" : defaultUrl);
    }

    /** 当前生效的 Prometheus 地址：DB（页面配置）优先，其次 yml 默认值 */
    private String prometheusUrl() {
        String v = config.getString("prometheus.url", defaultUrl);
        return normalize(v);
    }

    private String normalize(String url) {
        return url == null ? "" : url.trim().replaceAll("/+$", "");
    }

    @Override
    public double getCpuUsage(Long serverId) {
        return queryInstant("100 - (avg by (instance) (rate(node_cpu_seconds_total{"
                + labels(serverId, "mode=\"idle\"") + "}[15m])) * 100)");
    }

    @Override
    public double getMemoryUsage(Long serverId) {
        return queryInstant("(1 - node_memory_MemAvailable_bytes{" + labels(serverId)
                + "} / node_memory_MemTotal_bytes{" + labels(serverId) + "}) * 100");
    }

    @Override
    public double getDiskUsage(Long serverId) {
        return queryInstant("(1 - node_filesystem_avail_bytes{" + labels(serverId, "mountpoint=\"/\"")
                + "} / node_filesystem_size_bytes{" + labels(serverId, "mountpoint=\"/\"") + "}) * 100");
    }

    @Override
    public String getServerStatus(Long serverId) {
        double up = queryInstant("up{" + labels(serverId) + "}");
        if (up < 0) return "UNKNOWN";
        return up > 0 ? "ONLINE" : "OFFLINE";
    }

    @Override
    public MetricTrend getCpuUsageTrend(Long serverId, int hours) {
        MetricTrend t = queryRange("100 - (avg by (instance) (rate(node_cpu_seconds_total{"
                + labels(serverId, "mode=\"idle\"") + "}[15m])) * 100)", hours);
        return valid(t) ? t : emptyTrend();
    }

    @Override
    public MetricTrend getMemoryUsageTrend(Long serverId, int hours) {
        MetricTrend t = queryRange("(1 - node_memory_MemAvailable_bytes{" + labels(serverId)
                + "} / node_memory_MemTotal_bytes{" + labels(serverId) + "}) * 100", hours);
        return valid(t) ? t : emptyTrend();
    }

    // ==================== 标签组合 ====================

    /** 组合 Prometheus 标签选择器内容：base 匹配 + IP 的 instance 过滤；自动处理逗号，无匹配返回空串 */
    private String labels(Long serverId, String... base) {
        List<String> parts = new ArrayList<>();
        for (String b : base) if (b != null && !b.trim().isEmpty()) parts.add(b);
        String inst = instanceMatcher(serverId);
        if (inst != null) parts.add(inst);
        return String.join(",", parts);
    }

    /** 返回 instance=~"<ip>(:[0-9]+)?"；服务器无IP或不存在返回 null（聚合全部） */
    private String instanceMatcher(Long serverId) {
        if (serverId == null) return null;
        Optional<ServerAsset> s = serverRepository.findById(serverId);
        if (!s.isPresent() || s.get().getIp() == null || s.get().getIp().trim().isEmpty()) return null;
        return "instance=~\"" + s.get().getIp() + "(:[0-9]+)?\"";
    }

    private boolean valid(MetricTrend t) {
        return t != null && t.getTimes() != null && !t.getTimes().isEmpty();
    }

    private MetricTrend emptyTrend() {
        MetricTrend t = new MetricTrend();
        t.setTimes(new ArrayList<String>());
        t.setValues(new ArrayList<Double>());
        return t;
    }

    // ==================== 节点发现（同步用） ====================

    @Override
    public List<NodeInstance> discoverNodeInstances() {
        List<NodeInstance> out = new ArrayList<>();
        String base = prometheusUrl();
        if (base.isEmpty()) return out;
        try {
            URI uri = promUri(base, "/api/v1/query", "query", "up");
            ResponseEntity<String> resp = restTemplate.getForEntity(uri, String.class);
            JsonNode result = objectMapper.readTree(resp.getBody()).path("data").path("result");
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (JsonNode r : result) {
                String inst = r.path("metric").path("instance").asText("");
                String job = r.path("metric").path("job").asText("");
                double val = r.path("value").path(1).asDouble(-1);
                if (inst.isEmpty()) continue;
                int idx = inst.lastIndexOf(':');
                String ip = idx > 0 ? inst.substring(0, idx) : inst;
                String port = idx > 0 ? inst.substring(idx + 1) : "";
                boolean isNode = job.toLowerCase().contains("node") || "9100".equals(port) || "19100".equals(port);
                if (isNode && seen.add(ip)) {
                    out.add(new NodeInstance(ip, port, val > 0));
                }
            }
        } catch (Exception e) {
            log.warn("Prometheus 节点发现失败: {}", e.getMessage());
        }
        return out;
    }

    // ==================== Prometheus API ====================

    private double queryInstant(String promql) {
        String base = prometheusUrl();
        if (base.isEmpty()) return -1;
        try {
            URI uri = promUri(base, "/api/v1/query", "query", promql);
            ResponseEntity<String> resp = restTemplate.getForEntity(uri, String.class);
            JsonNode result = objectMapper.readTree(resp.getBody()).path("data").path("result");
            if (result.isArray() && result.size() > 0) {
                JsonNode value = result.get(0).path("value");
                if (value.isArray() && value.size() >= 2) {
                    return Math.round(Double.parseDouble(value.get(1).asText()) * 10.0) / 10.0;
                }
            }
        } catch (Exception e) {
            log.warn("Prometheus 即时查询失败({}): {}", base, e.getMessage());
        }
        return -1;
    }

    private MetricTrend queryRange(String promql, int hours) {
        String base = prometheusUrl();
        if (base.isEmpty()) return null;
        try {
            long now = System.currentTimeMillis() / 1000;
            long start = now - (long) hours * 3600;
            URI uri = promUri(base, "/api/v1/query_range",
                    "query", promql,
                    "start", String.valueOf(start),
                    "end", String.valueOf(now),
                    "step", "3600");
            ResponseEntity<String> resp = restTemplate.getForEntity(uri, String.class);
            JsonNode result = objectMapper.readTree(resp.getBody()).path("data").path("result");
            if (result.isArray() && result.size() > 0) {
                JsonNode values = result.get(0).path("values");
                List<String> times = new ArrayList<>();
                List<Double> vals = new ArrayList<>();
                DateTimeFormatter fmt = DateTimeFormatter.ofPattern("HH:mm");
                for (JsonNode pair : values) {
                    long ts = (long) pair.get(0).asDouble();
                    times.add(LocalDateTime.ofEpochSecond(ts, 0, java.time.ZoneOffset.ofHours(8)).format(fmt));
                    vals.add(Math.round(Double.parseDouble(pair.get(1).asText()) * 10.0) / 10.0);
                }
                MetricTrend t = new MetricTrend();
                t.setTimes(times);
                t.setValues(vals);
                return t;
            }
        } catch (Exception e) {
            log.warn("Prometheus 范围查询失败({}): {}", base, e.getMessage());
        }
        return null;
    }

    /**
     * 手动拼接 Prometheus HTTP URL。
     * 不能用 UriComponentsBuilder.encode()：它不会编码 query 值里的 '+'，而 Prometheus 会把 '+' 解析成空格，
     * 导致 instance=~"ip(:[0-9]+)?" 这类正则永远匹配不到。
     */
    private URI promUri(String base, String path, String... kv) {
        StringBuilder sb = new StringBuilder(base).append(path).append('?');
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (i > 0) sb.append('&');
            sb.append(kv[i]).append('=').append(urlEncode(kv[i + 1]));
        }
        return URI.create(sb.toString());
    }

    private String urlEncode(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return s == null ? "" : s;
        }
    }
}