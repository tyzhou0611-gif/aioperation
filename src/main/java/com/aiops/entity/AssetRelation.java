package com.aiops.entity;

import javax.persistence.*;
import java.time.LocalDateTime;

/** 资产关系（拓扑连线，用户手动配置） */
@Entity
@Table(name = "asset_relation",
        uniqueConstraints = @UniqueConstraint(name = "uk_relation",
                columnNames = {"source_type", "source_id", "target_type", "target_id", "relation_type"}))
public class AssetRelation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "source_type", nullable = false, length = 20) private String sourceType;
    @Column(name = "source_id", nullable = false) private Long sourceId;
    @Column(name = "target_type", nullable = false, length = 20) private String targetType;
    @Column(name = "target_id", nullable = false) private Long targetId;
    @Column(name = "relation_type", nullable = false, length = 30) private String relationType;
    @Column(length = 50) private String label;
    @Column(length = 255) private String description;
    @Column(name = "line_style", length = 20) private String lineStyle;
    @Column(name = "created_by") private Long createdBy;
    @Column(name = "create_time", updatable = false) private LocalDateTime createTime;
    @Column(name = "update_time") private LocalDateTime updateTime;

    public AssetRelation() {}
    public Long getId() { return id; } public void setId(Long v) { id = v; }
    public String getSourceType() { return sourceType; } public void setSourceType(String v) { sourceType = v; }
    public Long getSourceId() { return sourceId; } public void setSourceId(Long v) { sourceId = v; }
    public String getTargetType() { return targetType; } public void setTargetType(String v) { targetType = v; }
    public Long getTargetId() { return targetId; } public void setTargetId(Long v) { targetId = v; }
    public String getRelationType() { return relationType; } public void setRelationType(String v) { relationType = v; }
    public String getLabel() { return label; } public void setLabel(String v) { label = v; }
    public String getDescription() { return description; } public void setDescription(String v) { description = v; }
    public String getLineStyle() { return lineStyle; } public void setLineStyle(String v) { lineStyle = v; }
    public Long getCreatedBy() { return createdBy; } public void setCreatedBy(Long v) { createdBy = v; }
    public LocalDateTime getCreateTime() { return createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    @PrePersist protected void onCreate() {
        createTime = LocalDateTime.now(); updateTime = LocalDateTime.now();
        if (lineStyle == null) lineStyle = "solid";
    }
    @PreUpdate protected void onUpdate() { updateTime = LocalDateTime.now(); }
}