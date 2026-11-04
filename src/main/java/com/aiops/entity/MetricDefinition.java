package com.aiops.entity;

import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * 指标注册中心：一条记录 = 一个可被面板/告警/诊断复用的指标定义。
 * source_type:
 *   - prometheus: query 为 PromQL
 *   - http_json : query 为 URL，config_json 描述 headers/jsonPath
 *   - push      : 外部通过 POST /api/metrics/push 推送样本
 */
@Entity
@Table(name = "metric_definition")
public class MetricDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(name = "source_type", nullable = false, length = 20)
    private String sourceType;

    @Column(name = "query_expr", columnDefinition = "TEXT")
    private String query;

    @Column(name = "config_json", columnDefinition = "TEXT")
    private String configJson;

    @Column(name = "labels_json", length = 1000)
    private String labelsJson;

    @Column(length = 20)
    private String unit;

    @Column(name = "value_type", length = 20)
    private String valueType;

    @Column(name = "scope_type", length = 20)
    private String scopeType;

    @Column(name = "scope_id")
    private Long scopeId;

    @Column(name = "thresholds_json", length = 1000)
    private String thresholdsJson;

    private Integer builtin;

    private Integer enabled;

    @Column(length = 50)
    private String owner;

    @Column(updatable = false)
    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    public MetricDefinition() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }
    public String getConfigJson() { return configJson; }
    public void setConfigJson(String configJson) { this.configJson = configJson; }
    public String getLabelsJson() { return labelsJson; }
    public void setLabelsJson(String labelsJson) { this.labelsJson = labelsJson; }
    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }
    public String getValueType() { return valueType; }
    public void setValueType(String valueType) { this.valueType = valueType; }
    public String getScopeType() { return scopeType; }
    public void setScopeType(String scopeType) { this.scopeType = scopeType; }
    public Long getScopeId() { return scopeId; }
    public void setScopeId(Long scopeId) { this.scopeId = scopeId; }
    public String getThresholdsJson() { return thresholdsJson; }
    public void setThresholdsJson(String thresholdsJson) { this.thresholdsJson = thresholdsJson; }
    public Integer getBuiltin() { return builtin; }
    public void setBuiltin(Integer builtin) { this.builtin = builtin; }
    public Integer getEnabled() { return enabled; }
    public void setEnabled(Integer enabled) { this.enabled = enabled; }
    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }
    public LocalDateTime getCreateTime() { return createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }

    @PrePersist
    protected void onCreate() {
        createTime = LocalDateTime.now();
        updateTime = LocalDateTime.now();
        if (sourceType == null || sourceType.trim().isEmpty()) sourceType = "prometheus";
        if (unit == null || unit.trim().isEmpty()) unit = "num";
        if (valueType == null || valueType.trim().isEmpty()) valueType = "gauge";
        if (scopeType == null || scopeType.trim().isEmpty()) scopeType = "GLOBAL";
        if (builtin == null) builtin = 0;
        if (enabled == null) enabled = 1;
    }

    @PreUpdate
    protected void onUpdate() { updateTime = LocalDateTime.now(); }
}