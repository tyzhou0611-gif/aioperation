package com.aiops.infrastructure.ai;

/**
 * 诊断目标：服务器 / 中间件 / 应用 / 自定义问题。
 */
public class DiagnosisTarget {
    private String type = "SERVER";
    private Long id;
    private String name;
    private Long serverId;
    private String serverIp;
    /** 用户补充说明/自定义问题（可选） */
    private String extra;

    public String getType() { return type; }
    public void setType(String v) { type = v == null ? "SERVER" : v.trim().toUpperCase(); }
    public Long getId() { return id; }
    public void setId(Long v) { id = v; }
    public String getName() { return name; }
    public void setName(String v) { name = v; }
    public Long getServerId() { return serverId; }
    public void setServerId(Long v) { serverId = v; }
    public String getServerIp() { return serverIp; }
    public void setServerIp(String v) { serverIp = v; }
    public String getExtra() { return extra; }
    public void setExtra(String v) { extra = v; }

    public boolean isServer() { return "SERVER".equalsIgnoreCase(type); }
    public boolean isMiddleware() { return "MIDDLEWARE".equalsIgnoreCase(type); }
    public boolean isApp() { return "APP".equalsIgnoreCase(type); }
    public boolean isCustom() { return "CUSTOM".equalsIgnoreCase(type); }
}