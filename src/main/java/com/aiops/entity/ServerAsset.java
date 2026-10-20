package com.aiops.entity;

import javax.persistence.*;
import javax.validation.constraints.NotBlank;
import java.time.LocalDateTime;

/**
 * 服务器资产实体
 */
@Entity
@Table(name = "server_asset")
public class ServerAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "IP地址不能为空")
    @Column(nullable = false, length = 45)
    private String ip;

    @Column(length = 100)
    private String hostname;

    @Column(length = 100)
    private String os;

    @Column(length = 50)
    private String cpu;

    @Column(length = 50)
    private String memory;

    @Column(length = 50)
    private String disk;

    @Column(length = 20)
    private String status;

    @Column(length = 20)
    private String environment;

    @Column(name = "ssh_port")
    private Integer sshPort;

    @Column(name = "ssh_user", length = 50)
    private String sshUser;

    @Column(name = "ssh_password", length = 500)
    private String sshPassword;

    @Column(name = "ssh_password_encrypted", length = 500)
    private String sshPasswordEncrypted;

    @Column(name = "ssh_key", columnDefinition = "TEXT")
    private String sshKey;

    /** 出参：是否已设置密码（永不返回明文/密文） */
    @Transient
    private boolean sshPasswordSet;

    /** 入参：是否清除已保存的密码 */
    @Transient
    private boolean clearPassword;

    @Column(updatable = false)
    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    public ServerAsset() {}

    // ==================== Getters & Setters ====================

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getIp() { return ip; }
    public void setIp(String ip) { this.ip = ip; }

    public String getHostname() { return hostname; }
    public void setHostname(String hostname) { this.hostname = hostname; }

    public String getOs() { return os; }
    public void setOs(String os) { this.os = os; }

    public String getCpu() { return cpu; }
    public void setCpu(String cpu) { this.cpu = cpu; }

    public String getMemory() { return memory; }
    public void setMemory(String memory) { this.memory = memory; }

    public String getDisk() { return disk; }
    public void setDisk(String disk) { this.disk = disk; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }

    public Integer getSshPort() { return sshPort; }
    public void setSshPort(Integer sshPort) { this.sshPort = sshPort; }

    public String getSshUser() { return sshUser; }
    public void setSshUser(String sshUser) { this.sshUser = sshUser; }

    public String getSshPassword() { return sshPassword; }
    public void setSshPassword(String sshPassword) { this.sshPassword = sshPassword; }

    public String getSshPasswordEncrypted() { return sshPasswordEncrypted; }
    public void setSshPasswordEncrypted(String v) { this.sshPasswordEncrypted = v; }

    public boolean isSshPasswordSet() { return sshPasswordSet; }
    public void setSshPasswordSet(boolean v) { this.sshPasswordSet = v; }

    public boolean isClearPassword() { return clearPassword; }
    public void setClearPassword(boolean v) { this.clearPassword = v; }

    public String getSshKey() { return sshKey; }
    public void setSshKey(String sshKey) { this.sshKey = sshKey; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }

    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }

    @PrePersist
    protected void onCreate() {
        createTime = LocalDateTime.now();
        updateTime = LocalDateTime.now();
        if (status == null) status = "ONLINE";
        if (environment == null) environment = "production";
        if (sshPort == null) sshPort = 22;
        if (sshUser == null) sshUser = "root";
    }

    @PreUpdate
    protected void onUpdate() {
        updateTime = LocalDateTime.now();
    }
}