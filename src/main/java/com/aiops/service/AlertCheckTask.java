package com.aiops.service;

import java.util.Arrays;
import com.aiops.entity.AlertHistory;
import com.aiops.entity.AlertRule;
import com.aiops.entity.ServerAsset;
import com.aiops.infrastructure.metrics.MetricsProvider;
import com.aiops.infrastructure.notification.NotificationService;
import com.aiops.repository.ServerAssetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Component
public class AlertCheckTask {

    private static final Logger log = LoggerFactory.getLogger(AlertCheckTask.class);

    private final AlertService alertService;
    private final ServerAssetRepository serverRepository;
    private final MetricsProvider metricsProvider;
    private final NotificationService notificationService;
    private final ExecService execService;

    public AlertCheckTask(AlertService alertService, ServerAssetRepository serverRepository,
                          MetricsProvider metricsProvider, NotificationService notificationService,
                          ExecService execService) {
        this.alertService = alertService; this.serverRepository = serverRepository;
        this.metricsProvider = metricsProvider; this.notificationService = notificationService;
        this.execService = execService;
    }

    @Scheduled(fixedRate = 60_000)
    public void checkAlerts() {
        log.debug("===== 开始告警定时检测: {} =====",
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

        List<AlertRule> rules = alertService.getEnabledRules();
        if (rules.isEmpty()) return;

        List<ServerAsset> servers = serverRepository.findByStatus("ONLINE");
        if (servers.isEmpty()) { log.info("没有在线的服务器，跳过告警检测"); return; }

        int alertCount = 0;
        for (AlertRule rule : rules) {
            for (ServerAsset server : servers) {
                double actualValue = metricsProvider.getMetricValue(rule.getMetricName(), server.getId());
                if (!isThresholdTriggered(actualValue, rule.getThreshold(), rule.getComparator())) continue;

                alertCount++;
                String message = String.format(
                        "服务器 [%s](%s) %s = %.1f%%, 阈值 %.1f%%, 比较符 %s",
                        server.getHostname(), server.getIp(),
                        rule.getMetricName(), actualValue,
                        rule.getThreshold(), rule.getComparator());

                AlertHistory history = new AlertHistory();
                history.setRuleId(rule.getId()); history.setServerId(server.getId());
                history.setServerIp(server.getIp()); history.setMetricName(rule.getMetricName());
                history.setActualValue(actualValue); history.setThreshold(rule.getThreshold());
                history.setComparator(rule.getComparator()); history.setLevel(rule.getLevel());
                history.setMessage(message); history.setStatus("UNREAD");
                alertService.createHistory(history);
                log.warn("🚨 告警触发 [{}] {}", rule.getLevel(), message);

                notificationService.notify(rule.getNotifyChannels(), rule.getNotifyConfig(),
                        "AI-Ops " + rule.getLevel() + " 告警", message);

                if (rule.getAutoRecoveryEnabled() != null && rule.getAutoRecoveryEnabled() == 1
                        && rule.getAutoRecoveryScript() != null && !rule.getAutoRecoveryScript().trim().isEmpty()) {
                    java.util.List<SshResult> results = execService.executeOnServers(Arrays.asList(server.getId()), rule.getAutoRecoveryScript());
                    for (SshResult r : results) {
                        log.info("🛠 自愈执行 [{}] {}", server.getHostname(), r.isSuccess() ? "成功" : "失败");
                        history.setAutoHealStatus(r.isSuccess() ? "SUCCESS" : "FAILED");
                        history.setAutoHealResult(r.isSuccess() ? "自愈脚本执行成功" : "失败: " + r.getErrorMessage());
                        if (!r.isSuccess()) {
                            history.setLevel("CRITICAL");
                            log.warn("⚠ 自愈失败，告警升级为 CRITICAL");
                        }
                    }
                }
            }
        }
        if (alertCount > 0) log.info("告警检测完成，本次触发 {} 条告警", alertCount);
    }

    private boolean isThresholdTriggered(double actualValue, double threshold, String comparator) {
        if (comparator == null) return false;
        switch (comparator) {
            case ">":  return actualValue > threshold;
            case "<":  return actualValue < threshold;
            case ">=": return actualValue >= threshold;
            case "<=": return actualValue <= threshold;
            case "=": case "==": return Math.abs(actualValue - threshold) < 0.01;
            default: return false;
        }
    }
}