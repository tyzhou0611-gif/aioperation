package com.aiops.controller.vo;

/** 代码块建议执行的目标服务器 */
public class SuggestedServer {
    private Long id;
    private String name;
    private String ip;
    private String reason;

    public SuggestedServer() {}
    public SuggestedServer(Long id, String name, String ip, String reason) {
        this.id = id; this.name = name; this.ip = ip; this.reason = reason;
    }
    public Long getId() { return id; } public void setId(Long v) { id = v; }
    public String getName() { return name; } public void setName(String v) { name = v; }
    public String getIp() { return ip; } public void setIp(String v) { ip = v; }
    public String getReason() { return reason; } public void setReason(String v) { reason = v; }
}