package com.aiops.infrastructure.metrics;

import com.aiops.service.ConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

/**
 * 启动时打印当前指标源信息（运行期可在系统设置中调整）。
 */
@Component
public class MetricsStartupLogger {

    private static final Logger log = LoggerFactory.getLogger(MetricsStartupLogger.class);

    private final ConfigService config;

    public MetricsStartupLogger(ConfigService config) {
        this.config = config;
    }

    @PostConstruct
    public void logActiveSource() {
        String source = config.getString("metrics.source", "prometheus");
        String prometheusUrl = config.getString("prometheus.url", "");
        String grafanaUrl = config.getString("grafana.url", "");
        String sourceLabel = "default".equalsIgnoreCase(source) ? "本地模拟" : "Prometheus";
        log.info("========================================");
        log.info("  当前指标源：{} ({})", sourceLabel, source);
        if (!"default".equalsIgnoreCase(source)) {
            log.info("  Prometheus 地址：{}", prometheusUrl.isEmpty() ? "(未配置，请在系统设置中填写)" : prometheusUrl);
        }
        log.info("  Grafana 地址：{}", grafanaUrl.isEmpty() ? "未配置" : grafanaUrl);
        log.info("========================================");
    }
}