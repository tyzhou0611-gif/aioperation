package com.aiops.controller.vo;

/**
 * 拓扑图边（连线）
 */
public class TopoEdge {
    private String source;     // 起点节点 id
    private String target;     // 终点节点 id
    private String label;      // 关系：依赖 / 调用 / 部署
    private String lineStyle;  // solid / dashed / gray
    private int category;      // 0 应用→中间件, 1 应用→应用, 2 →服务器(部署)
    private String origin = "manual";  // manual(用户配置实线) / auto(系统推导虚线)
    private Long relationId;   // 手动关系ID(auto为null)，供删除/编辑
    private String sourceKey;  // SRV:id 原始类型:ID
    private String targetKey;  // TGT:id
    private String relationType; // DEPENDS/CALLS/DEPLOY_ON/CONNECTS_TO

    public TopoEdge() {}

    public TopoEdge(String source, String target, String label, String lineStyle, int category) {
        this.source = source; this.target = target; this.label = label; this.lineStyle = lineStyle; this.category = category;
    }

    public TopoEdge(String source, String target, String label, String lineStyle, int category,
                    String origin, Long relationId, String sourceKey, String targetKey, String relationType) {
        this(source, target, label, lineStyle, category);
        this.origin = origin; this.relationId = relationId;
        this.sourceKey = sourceKey; this.targetKey = targetKey; this.relationType = relationType;
    }

    public String getSource() { return source; } public void setSource(String v) { source = v; }
    public String getTarget() { return target; } public void setTarget(String v) { target = v; }
    public String getLabel() { return label; } public void setLabel(String v) { label = v; }
    public String getLineStyle() { return lineStyle; } public void setLineStyle(String v) { lineStyle = v; }
    public int getCategory() { return category; } public void setCategory(int v) { category = v; }
    public String getOrigin() { return origin; } public void setOrigin(String v) { origin = v; }
    public Long getRelationId() { return relationId; } public void setRelationId(Long v) { relationId = v; }
    public String getSourceKey() { return sourceKey; } public void setSourceKey(String v) { sourceKey = v; }
    public String getTargetKey() { return targetKey; } public void setTargetKey(String v) { targetKey = v; }
    public String getRelationType() { return relationType; } public void setRelationType(String v) { relationType = v; }
}