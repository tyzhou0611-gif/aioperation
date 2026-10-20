package com.aiops.infrastructure.metrics;

import com.aiops.entity.ServerAsset;
import com.aiops.repository.ServerAssetRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

/**
 * Prometheus 指标提供者（真实数据源 + 黑盒降级）
 *
 * 当 app.metrics.source=prometheus 时激活。
 * - 用 node_exporter 指标按 instance 标签（服务器 IP）过滤，查询真实 CPU/内存/磁盘。
 * - 用 Jackson 正确解析 Prometheus /api/v1/query 与 /api/v1/query_range 的 JSON。
 * - 若查不到数据或异常，降级为模拟值，保证仪表盘与告警不空白。
 */
@Component
@ConditionalOnProperty(name = "app.metrics.source", havingValue = "prometheus")
public class PrometheusMetricsProvider implements MetricsProvider {

    private static final Logger log = LoggerFactory.getLogger(PrometheusMetricsProvider.class);

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ServerAssetRepository serverRepository;
    private final String prometheusUrl;
    private final Random random = new Random();

    public PrometheusMetricsProvider(
            @Value("${app.prometheus.url}") String prometheusUrl,
            ServerAssetRepository serverRepository) {
        this.prometheusUrl = prometheusUrl;
        this.serverRepository = serverRepository;
        log.info("PrometheusMetricsProvider 已初始化，Prometheus地址: {}", prometheusUrl);
    }

    @Override
    public double getCpuUsage(Long serverId) {
        double v = queryInstant("100 - (avg by (instance) (rate(node_cpu_seconds_total{"
                + labels(serverId, "mode=\"idle\"") + "}[5m])) * 100)");
        return v >= 0 ? v : simulate(55);
    }

    @Override
    public double getMemoryUsage(Long serverId) {
        double v = queryInstant("(1 - node_memory_MemAvailable_bytes{" + labels(serverId)
                + "} / node_memory_MemTotal_bytes{" + labels(serverId) + "}) * 100");
        return v >= 0 ? v : simulate(65);
    }

    @Override
    public double getDiskUsage(Long serverId) {
        double v = queryInstant("(1 - node_filesystem_avail_bytes{" + labels(serverId, "mountpoint=\"/\"")
                + "} / node_filesystem_size_bytes{" + labels(serverId, "mountpoint=\"/\"") + "}) * 100");
        return v >= 0 ? v : simulate(60);
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
                + labels(serverId, "mode=\"idle\"") + "}[5m])) * 100)", hours);
        return valid(t) ? t : simulateTrend(hours, true);
    }

    @Override
    public MetricTrend getMemoryUsageTrend(Long serverId, int hours) {
        MetricTrend t = queryRange("(1 - node_memory_MemAvailable_bytes{" + labels(serverId)
                + "} / node_memory_MemTotal_bytes{" + labels(serverId) + "}) * 100", hours);
        return valid(t) ? t : simulateTrend(hours, false);
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

    // ==================== 节点发现（同步用） ====================

    @Override
    public List<NodeInstance> discoverNodeInstances() {
        List<NodeInstance> out = new ArrayList<>();
        try {
            java.net.URI uri = UriComponentsBuilder.fromHttpUrl(prometheusUrl + "/api/v1/query")
                    .queryParam("query", "up").build().encode().toUri();
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
        try {
            java.net.URI uri = UriComponentsBuilder.fromHttpUrl(prometheusUrl + "/api/v1/query")
                    .queryParam("query", promql).build().encode().toUri();
            ResponseEntity<String> resp = restTemplate.getForEntity(uri, String.class);
            JsonNode result = objectMapper.readTree(resp.getBody()).path("data").path("result");
            if (result.isArray() && result.size() > 0) {
                JsonNode value = result.get(0).path("value");
                if (value.isArray() && value.size() >= 2) {
                    return Math.round(Double.parseDouble(value.get(1).asText()) * 10.0) / 10.0;
                }
            }
        } catch (Exception e) {
            log.warn("Prometheus 即时查询失败（降级模拟）: {}", e.getMessage());
        }
        return -1;
    }

    private MetricTrend queryRange(String promql, int hours) {
        try {
            long now = System.currentTimeMillis() / 1000;
            long start = now - (long) hours * 3600;
            java.net.URI uri = UriComponentsBuilder.fromHttpUrl(prometheusUrl + "/api/v1/query_range")
                    .queryParam("query", promql)
                    .queryParam("start", String.valueOf(start))
                    .queryParam("end", String.valueOf(now))
                    .queryParam("step", "3600")
                    .build().encode().toUri();
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
            log.warn("Prometheus 范围查询失败（降级模拟）: {}", e.getMessage());
        }
        return null;
    }

    // ==================== 降级模拟 ====================

    private double simulate(double base) {
        return Math.round(Math.min(base + random.nextDouble() * 25, 99.5) * 10.0) / 10.0;
    }

    private MetricTrend simulateTrend(int hours, boolean isCpu) {
        List<String> times = new ArrayList<>();
        List<Double> values = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        double base = isCpu ? 40 : 60;
        for (int i = hours - 1; i >= 0; i--) {
            LocalDateTime p = now.minusHours(i);
            times.add(String.format("%02d:%02d", p.getHour(), p.getMinute()));
            double v = base + random.nextDouble() * (isCpu ? 30 : 20);
            values.add(Math.round(Math.min(v, 99.5) * 10.0) / 10.0);
        }
        MetricTrend t = new MetricTrend();
        t.setTimes(times);
        t.setValues(values);
        return t;
    }
}