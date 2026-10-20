package com.aiops.controller.vo;

/**
 * 拓扑图节点
 */
public class TopoNode {
    private String id;         // 形如 "APP:1" / "MW:2" / "SRV:3"
    private String name;
    private String category;   // APP / MIDDLEWARE / SERVER
    private int symbolSize;
    private String detail;     // 悬停气泡内容（IP/端口/版本/状态）
    private String env;        // 环境，用于筛选
    private String status;     // 状态，用于筛选

    public TopoNode() {}

    public TopoNode(String id, String name, String category, int symbolSize, String detail) {
        this(id, name, category, symbolSize, detail, null, null);
    }

    public TopoNode(String id, String name, String category, int symbolSize, String detail, String env, String status) {
        this.id = id; this.name = name; this.category = category; this.symbolSize = symbolSize;
        this.detail = detail; this.env = env; this.status = status;
    }

    public String getId() { return id; } public void setId(String v) { id = v; }
    public String getName() { return name; } public void setName(String v) { name = v; }
    public String getCategory() { return category; } public void setCategory(String v) { category = v; }
    public int getSymbolSize() { return symbolSize; } public void setSymbolSize(int v) { symbolSize = v; }
    public String getDetail() { return detail; } public void setDetail(String v) { detail = v; }
    public String getEnv() { return env; } public void setEnv(String v) { env = v; }
    public String getStatus() { return status; } public void setStatus(String v) { status = v; }
}