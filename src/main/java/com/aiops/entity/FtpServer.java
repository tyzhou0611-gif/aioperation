package com.aiops.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import javax.persistence.*;
import java.time.LocalDateTime;

/** FTP / SFTP 资产（用于从 FTP 拉取文件并分发到受管服务器） */
@Entity
@Table(name = "ftp_server")
public class FtpServer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 20)
    private String protocol;

    @Column(nullable = false, length = 255)
    private String host;

    private Integer port;

    @Column(length = 100)
    private String username;

    @JsonIgnore
    @Column(name = "password_encrypted", length = 500)
    private String passwordEncrypted;

    @Column(name = "base_path", length = 500)
    private String basePath;

    @Column(name = "passive_mode")
    private Integer passiveMode;

    private Integer enabled;

    @Column(length = 500)
    private String description;

    @Column(updatable = false)
    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @Transient
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;

    @Transient
    private boolean passwordSet;

    public FtpServer() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getProtocol() { return protocol; }
    public void setProtocol(String protocol) { this.protocol = protocol; }
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public Integer getPort() { return port; }
    public void setPort(Integer port) { this.port = port; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPasswordEncrypted() { return passwordEncrypted; }
    public void setPasswordEncrypted(String passwordEncrypted) { this.passwordEncrypted = passwordEncrypted; }
    public String getBasePath() { return basePath; }
    public void setBasePath(String basePath) { this.basePath = basePath; }
    public Integer getPassiveMode() { return passiveMode; }
    public void setPassiveMode(Integer passiveMode) { this.passiveMode = passiveMode; }
    public Integer getEnabled() { return enabled; }
    public void setEnabled(Integer enabled) { this.enabled = enabled; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public LocalDateTime getCreateTime() { return createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public boolean isPasswordSet() { return passwordSet; }
    public void setPasswordSet(boolean passwordSet) { this.passwordSet = passwordSet; }

    @PrePersist
    protected void onCreate() {
        createTime = LocalDateTime.now();
        updateTime = LocalDateTime.now();
        if (protocol == null || protocol.trim().isEmpty()) protocol = "FTP";
        if (port == null || port <= 0) port = "SFTP".equalsIgnoreCase(protocol) ? 22 : 21;
        if (passiveMode == null) passiveMode = 1;
        if (enabled == null) enabled = 1;
        if (basePath == null) basePath = "/";
    }

    @PreUpdate
    protected void onUpdate() { updateTime = LocalDateTime.now(); }
}