package com.aiops.entity;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity @Table(name = "elk_connection")
public class ElkConnection {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 100) private String name;
    @Column(name = "es_url", nullable = false, length = 200) private String esUrl;
    @Column(length = 50) private String esUsername;
    @Column(name = "es_password_encrypted", length = 500) private String esPasswordEncrypted;
    @Column(name = "logstash_url", length = 200) private String logstashUrl;
    @Column(length = 20) private String status;
    @Column(name = "create_time", updatable = false) private LocalDateTime createTime;
    @Column(name = "update_time") private LocalDateTime updateTime;
    @Transient private String esPassword;
    @Transient private boolean passwordSet;
    public ElkConnection() {}
    public Long getId() { return id; } public void setId(Long v) { id = v; }
    public String getName() { return name; } public void setName(String v) { name = v; }
    public String getEsUrl() { return esUrl; } public void setEsUrl(String v) { esUrl = v; }
    public String getEsUsername() { return esUsername; } public void setEsUsername(String v) { esUsername = v; }
    public String getEsPasswordEncrypted() { return esPasswordEncrypted; } public void setEsPasswordEncrypted(String v) { esPasswordEncrypted = v; }
    public String getLogstashUrl() { return logstashUrl; } public void setLogstashUrl(String v) { logstashUrl = v; }
    public String getStatus() { return status; } public void setStatus(String v) { status = v; }
    public LocalDateTime getCreateTime() { return createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public String getEsPassword() { return esPassword; } public void setEsPassword(String v) { esPassword = v; }
    public boolean isPasswordSet() { return passwordSet; } public void setPasswordSet(boolean v) { passwordSet = v; }
    @PrePersist protected void onCreate() { createTime = LocalDateTime.now(); updateTime = LocalDateTime.now(); }
    @PreUpdate protected void onUpdate() { updateTime = LocalDateTime.now(); }
}