package com.aiops.infrastructure.metrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.annotation.PostConstruct;

/**
 * 启动时打印当前指标源信息
 */
@Component
public class MetricsStartupLogger {

    private static final Logger log = LoggerFactory.getLogger(MetricsStartupLogger.class);

    private final String metricsSource;
    private final String prometheusUrl;
    private final String grafanaUrl;

    public MetricsStartupLogger(
            @Value("${app.metrics.source:default}") String metricsSource,
            @Value("${app.prometheus.url:}") String prometheusUrl,
            @Value("${app.grafana.url:}") String grafanaUrl) {
        this.metricsSource = metricsSource;
        this.prometheusUrl = prometheusUrl;
        this.grafanaUrl = grafanaUrl;
    }

    @PostConstruct
    public void logActiveSource() {
        String sourceLabel = "default".equalsIgnoreCase(metricsSource) ? "本地模拟" : "Prometheus";
        log.info("========================================");
        log.info("  当前指标源：{} ({})", sourceLabel, metricsSource);
        if (!"default".equalsIgnoreCase(metricsSource)) {
            log.info("  Prometheus 地址：{}", prometheusUrl);
        }
        log.info("  Grafana 地址：{}", grafanaUrl != null ? grafanaUrl : "未配置");
        log.info("========================================");
    }
}