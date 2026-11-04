package com.aiops.infrastructure.metrics;

import com.aiops.service.ConfigService;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 指标源路由：按 sys_config 中的 metrics.source 在 Prometheus / 本地模拟之间动态切换。
 * 页面保存后立即生效，无需重启；Prometheus 地址同样由 PrometheusMetricsProvider 每次请求读取。
 */
@Component
@Primary
public class DynamicMetricsProvider implements MetricsProvider {

    private final PrometheusMetricsProvider prometheusProvider;
    private final DefaultMetricsProvider defaultProvider;
    private final ConfigService config;

    public DynamicMetricsProvider(PrometheusMetricsProvider prometheusProvider,
                                  DefaultMetricsProvider defaultProvider,
                                  ConfigService config) {
        this.prometheusProvider = prometheusProvider;
        this.defaultProvider = defaultProvider;
        this.config = config;
    }

    private MetricsProvider active() {
        String source = config.getString("metrics.source", "prometheus");
        return "default".equalsIgnoreCase(source) ? defaultProvider : prometheusProvider;
    }

    @Override
    public double getCpuUsage(Long serverId) { return active().getCpuUsage(serverId); }

    @Override
    public double getMemoryUsage(Long serverId) { return active().getMemoryUsage(serverId); }

    @Override
    public double getDiskUsage(Long serverId) { return active().getDiskUsage(serverId); }

    @Override
    public String getServerStatus(Long serverId) { return active().getServerStatus(serverId); }

    @Override
    public MetricTrend getCpuUsageTrend(Long serverId, int hours) { return active().getCpuUsageTrend(serverId, hours); }

    @Override
    public MetricTrend getMemoryUsageTrend(Long serverId, int hours) { return active().getMemoryUsageTrend(serverId, hours); }

    @Override
    public List<NodeInstance> discoverNodeInstances() { return active().discoverNodeInstances(); }
}