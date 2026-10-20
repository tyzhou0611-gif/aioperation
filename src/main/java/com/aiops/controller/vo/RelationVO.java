package com.aiops.controller.vo;

/** 关系列表/表格视图（含资产名与来源） */
public class RelationVO {
    private Long id;
    private String sourceType;
    private Long sourceId;
    private String sourceName;
    private String targetType;
    private Long targetId;
    private String targetName;
    private String relationType;
    private String label;
    private String description;
    private String lineStyle;
    private String origin = "manual";

    public RelationVO() {}
    public Long getId() { return id; } public void setId(Long v) { id = v; }
    public String getSourceType() { return sourceType; } public void setSourceType(String v) { sourceType = v; }
    public Long getSourceId() { return sourceId; } public void setSourceId(Long v) { sourceId = v; }
    public String getSourceName() { return sourceName; } public void setSourceName(String v) { sourceName = v; }
    public String getTargetType() { return targetType; } public void setTargetType(String v) { targetType = v; }
    public Long getTargetId() { return targetId; } public void setTargetId(Long v) { targetId = v; }
    public String getTargetName() { return targetName; } public void setTargetName(String v) { targetName = v; }
    public String getRelationType() { return relationType; } public void setRelationType(String v) { relationType = v; }
    public String getLabel() { return label; } public void setLabel(String v) { label = v; }
    public String getDescription() { return description; } public void setDescription(String v) { description = v; }
    public String getLineStyle() { return lineStyle; } public void setLineStyle(String v) { lineStyle = v; }
    public String getOrigin() { return origin; } public void setOrigin(String v) { origin = v; }
}