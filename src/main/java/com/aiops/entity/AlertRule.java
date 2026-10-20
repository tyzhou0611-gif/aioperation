package com.aiops.entity;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity @Table(name = "alert_rule")
public class AlertRule {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "rule_name", nullable = false, length = 100)
    private String ruleName;
    @Column(name = "metric_name", nullable = false, length = 50)
    private String metricName;
    @Column(nullable = false, length = 5)
    private String comparator;
    @Column(nullable = false)
    private Double threshold;
    @Column(length = 20)
    private String level;
    @Column(name = "notify_email", length = 200)
    private String notifyEmail;
    @Column(name = "notify_channels", length = 500)
    private String notifyChannels;
    @Column(name = "notify_config", length = 2000)
    private String notifyConfig;
    @Column(name = "auto_recovery_enabled")
    private Integer autoRecoveryEnabled;
    @Column(name = "auto_recovery_script", columnDefinition = "TEXT")
    private String autoRecoveryScript;
    private Integer enabled;
    @Column(updatable = false) private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public AlertRule() {}
    public Long getId() { return id; } public void setId(Long id) { this.id = id; }
    public String getRuleName() { return ruleName; } public void setRuleName(String v) { this.ruleName = v; }
    public String getMetricName() { return metricName; } public void setMetricName(String v) { this.metricName = v; }
    public String getComparator() { return comparator; } public void setComparator(String v) { this.comparator = v; }
    public Double getThreshold() { return threshold; } public void setThreshold(Double v) { this.threshold = v; }
    public String getLevel() { return level; } public void setLevel(String v) { this.level = v; }
    public String getNotifyEmail() { return notifyEmail; } public void setNotifyEmail(String v) { this.notifyEmail = v; }
    public String getNotifyChannels() { return notifyChannels; } public void setNotifyChannels(String v) { this.notifyChannels = v; }
    public String getNotifyConfig() { return notifyConfig; } public void setNotifyConfig(String v) { this.notifyConfig = v; }
    public Integer getAutoRecoveryEnabled() { return autoRecoveryEnabled; } public void setAutoRecoveryEnabled(Integer v) { this.autoRecoveryEnabled = v; }
    public String getAutoRecoveryScript() { return autoRecoveryScript; } public void setAutoRecoveryScript(String v) { this.autoRecoveryScript = v; }
    public Integer getEnabled() { return enabled; } public void setEnabled(Integer v) { this.enabled = v; }
    public LocalDateTime getCreateTime() { return createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }

    @PrePersist protected void onCreate() {
        createTime = LocalDateTime.now(); updateTime = LocalDateTime.now();
        if (enabled == null) enabled = 1; if (level == null) level = "WARNING";
        if (comparator == null) comparator = ">"; if (autoRecoveryEnabled == null) autoRecoveryEnabled = 0;
    }
    @PreUpdate protected void onUpdate() { updateTime = LocalDateTime.now(); }
}