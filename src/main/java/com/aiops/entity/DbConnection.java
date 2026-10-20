package com.aiops.entity;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity @Table(name = "db_connection")
public class DbConnection {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 100) private String name;
    @Column(name = "db_type", nullable = false, length = 30) private String dbType;
    @Column(nullable = false, length = 100) private String host;
    @Column(nullable = false) private Integer port;
    @Column(name = "database_name", length = 100) private String databaseName;
    @Column(length = 50) private String username;
    @Column(name = "password_encrypted", length = 500) private String passwordEncrypted;
    @Column(name = "middleware_id") private Long middlewareId;
    @Column(length = 20) private String status;
    @Column(name = "last_connect_time") private LocalDateTime lastConnectTime;
    @Column(name = "create_time", updatable = false) private LocalDateTime createTime;
    @Column(name = "update_time") private LocalDateTime updateTime;
    /** 入参：明文密码（不落库） */
    @Transient private String password;
    /** 出参：是否已设置密码 */
    @Transient private boolean passwordSet;

    public DbConnection() {}
    public Long getId() { return id; } public void setId(Long v) { id = v; }
    public String getName() { return name; } public void setName(String v) { name = v; }
    public String getDbType() { return dbType; } public void setDbType(String v) { dbType = v; }
    public String getHost() { return host; } public void setHost(String v) { host = v; }
    public Integer getPort() { return port; } public void setPort(Integer v) { port = v; }
    public String getDatabaseName() { return databaseName; } public void setDatabaseName(String v) { databaseName = v; }
    public String getUsername() { return username; } public void setUsername(String v) { username = v; }
    public String getPasswordEncrypted() { return passwordEncrypted; } public void setPasswordEncrypted(String v) { passwordEncrypted = v; }
    public Long getMiddlewareId() { return middlewareId; } public void setMiddlewareId(Long v) { middlewareId = v; }
    public String getStatus() { return status; } public void setStatus(String v) { status = v; }
    public LocalDateTime getLastConnectTime() { return lastConnectTime; } public void setLastConnectTime(LocalDateTime v) { lastConnectTime = v; }
    public LocalDateTime getCreateTime() { return createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public String getPassword() { return password; } public void setPassword(String v) { password = v; }
    public boolean isPasswordSet() { return passwordSet; } public void setPasswordSet(boolean v) { passwordSet = v; }
    @PrePersist protected void onCreate() { createTime = LocalDateTime.now(); updateTime = LocalDateTime.now(); }
    @PreUpdate protected void onUpdate() { updateTime = LocalDateTime.now(); }
}