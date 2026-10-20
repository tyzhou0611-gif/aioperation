package com.aiops.entity;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity @Table(name = "sys_log_simulate")
public class SimulatedLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "server_id") private Long serverId;
    @Column(name = "server_ip", length = 45) private String serverIp;
    @Column(name = "log_level", length = 20) private String logLevel;
    @Column(name = "log_source", length = 100) private String logSource;
    @Column(columnDefinition = "TEXT") private String message;
    @Column(updatable = false) private LocalDateTime createTime;

    public SimulatedLog() {}
    public Long getId() { return id; }
    public Long getServerId() { return serverId; } public void setServerId(Long v) { this.serverId = v; }
    public String getServerIp() { return serverIp; } public void setServerIp(String v) { this.serverIp = v; }
    public String getLogLevel() { return logLevel; } public void setLogLevel(String v) { this.logLevel = v; }
    public String getLogSource() { return logSource; } public void setLogSource(String v) { this.logSource = v; }
    public String getMessage() { return message; } public void setMessage(String v) { this.message = v; }
    public LocalDateTime getCreateTime() { return createTime; }
    @PrePersist protected void onCreate() { createTime = LocalDateTime.now(); }
}