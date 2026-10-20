package com.aiops.entity;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity @Table(name = "sys_config")
public class SysConfig {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "config_key", nullable = false, unique = true, length = 100)
    private String configKey;
    @Column(name = "config_value", length = 2000) private String configValue;
    @Column(name = "config_group", length = 50) private String configGroup;
    @Column(length = 500) private String description;
    @Column(name = "create_time", updatable = false) private LocalDateTime createTime;
    @Column(name = "update_time") private LocalDateTime updateTime;

    public SysConfig() {}
    public Long getId() { return id; } public void setId(Long v) { id = v; }
    public String getConfigKey() { return configKey; } public void setConfigKey(String v) { configKey = v; }
    public String getConfigValue() { return configValue; } public void setConfigValue(String v) { configValue = v; }
    public String getConfigGroup() { return configGroup; } public void setConfigGroup(String v) { configGroup = v; }
    public String getDescription() { return description; } public void setDescription(String v) { description = v; }
    public LocalDateTime getCreateTime() { return createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    @PrePersist protected void onCreate() { createTime = LocalDateTime.now(); updateTime = LocalDateTime.now(); }
    @PreUpdate protected void onUpdate() { updateTime = LocalDateTime.now(); }
}