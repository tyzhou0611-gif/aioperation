package com.aiops.controller.vo;

/** AI 识别出的关联资产 */
public class AssetRef {
    private String type;
    private String name;
    private Long id;

    public AssetRef() {}
    public AssetRef(String type, String name, Long id) { this.type = type; this.name = name; this.id = id; }
    public String getType() { return type; } public void setType(String v) { type = v; }
    public String getName() { return name; } public void setName(String v) { name = v; }
    public Long getId() { return id; } public void setId(Long v) { id = v; }
}