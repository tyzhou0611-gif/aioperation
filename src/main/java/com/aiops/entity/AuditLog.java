package com.aiops.entity;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity @Table(name = "sys_audit_log")
public class AuditLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(length = 50) private String username;
    @Column(name = "ip_address", length = 45) private String ipAddress;
    @Column(name = "request_url", length = 500) private String requestUrl;
    @Column(name = "http_method", length = 10) private String httpMethod;
    @Column(name = "request_params", columnDefinition = "TEXT") private String requestParams;
    @Column(name = "response_status") private Integer responseStatus;
    @Column(name = "duration_ms") private Long durationMs;
    @Column(updatable = false) private LocalDateTime createTime;

    public AuditLog() {}
    public Long getId() { return id; }
    public String getUsername() { return username; } public void setUsername(String v) { this.username = v; }
    public String getIpAddress() { return ipAddress; } public void setIpAddress(String v) { this.ipAddress = v; }
    public String getRequestUrl() { return requestUrl; } public void setRequestUrl(String v) { this.requestUrl = v; }
    public String getHttpMethod() { return httpMethod; } public void setHttpMethod(String v) { this.httpMethod = v; }
    public String getRequestParams() { return requestParams; } public void setRequestParams(String v) { this.requestParams = v; }
    public Integer getResponseStatus() { return responseStatus; } public void setResponseStatus(Integer v) { this.responseStatus = v; }
    public Long getDurationMs() { return durationMs; } public void setDurationMs(Long v) { this.durationMs = v; }
    public LocalDateTime getCreateTime() { return createTime; }
    @PrePersist protected void onCreate() { createTime = LocalDateTime.now(); }
}