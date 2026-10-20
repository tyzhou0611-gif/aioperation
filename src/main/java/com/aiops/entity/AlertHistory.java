package com.aiops.entity;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity @Table(name = "alert_history")
public class AlertHistory {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "rule_id") private Long ruleId;
    @Column(name = "server_id") private Long serverId;
    @Column(name = "server_ip", length = 45) private String serverIp;
    @Column(name = "metric_name", nullable = false, length = 50) private String metricName;
    @Column(name = "actual_value", nullable = false) private Double actualValue;
    @Column(nullable = false) private Double threshold;
    @Column(length = 5) private String comparator;
    @Column(length = 20) private String level;
    @Column(length = 500) private String message;
    @Column(length = 20) private String status;
    @Column(name = "auto_heal_status", length = 20) private String autoHealStatus;
    @Column(name = "auto_heal_result", length = 500) private String autoHealResult;
    @Column(updatable = false) private LocalDateTime createTime;

    public AlertHistory() {}
    public Long getId() { return id; } public void setId(Long v) { this.id = v; }
    public Long getRuleId() { return ruleId; } public void setRuleId(Long v) { this.ruleId = v; }
    public Long getServerId() { return serverId; } public void setServerId(Long v) { this.serverId = v; }
    public String getServerIp() { return serverIp; } public void setServerIp(String v) { this.serverIp = v; }
    public String getMetricName() { return metricName; } public void setMetricName(String v) { this.metricName = v; }
    public Double getActualValue() { return actualValue; } public void setActualValue(Double v) { this.actualValue = v; }
    public Double getThreshold() { return threshold; } public void setThreshold(Double v) { this.threshold = v; }
    public String getComparator() { return comparator; } public void setComparator(String v) { this.comparator = v; }
    public String getLevel() { return level; } public void setLevel(String v) { this.level = v; }
    public String getMessage() { return message; } public void setMessage(String v) { this.message = v; }
    public String getStatus() { return status; } public void setStatus(String v) { this.status = v; }
    public String getAutoHealStatus() { return autoHealStatus; } public void setAutoHealStatus(String v) { this.autoHealStatus = v; }
    public String getAutoHealResult() { return autoHealResult; } public void setAutoHealResult(String v) { this.autoHealResult = v; }
    public LocalDateTime getCreateTime() { return createTime; }

    @PrePersist protected void onCreate() {
        createTime = LocalDateTime.now();
        if (status == null) status = "UNREAD"; if (level == null) level = "WARNING";
    }
}