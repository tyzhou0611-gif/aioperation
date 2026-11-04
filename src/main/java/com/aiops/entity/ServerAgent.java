package com.aiops.entity;

import javax.persistence.*;
import java.time.LocalDateTime;

/** 服务器上已安装/最近安装的 Prometheus Exporter 记录（用于 HTTP SD 自动发现） */
@Entity
@Table(name = "server_agent", uniqueConstraints = {
        @UniqueConstraint(name = "uk_server_agent", columnNames = {"server_id", "exporter"})
})
public class ServerAgent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "server_id", nullable = false)
    private Long serverId;

    @Column(nullable = false, length = 50)
    private String exporter;

    @Column(name = "service_name", length = 100)
    private String serviceName;

    private Integer port;

    @Column(name = "install_dir", length = 300)
    private String installDir;

    @Column(name = "package_name", length = 200)
    private String packageName;

    @Column(length = 50)
    private String version;

    @Column(length = 20)
    private String status;

    @Column(length = 2000)
    private String message;

    @Column(name = "installed_at")
    private LocalDateTime installedAt;

    @Column(name = "update_time")
    private LocalDateTime updateTime;

    public ServerAgent() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getServerId() { return serverId; }
    public void setServerId(Long serverId) { this.serverId = serverId; }
    public String getExporter() { return exporter; }
    public void setExporter(String exporter) { this.exporter = exporter; }
    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }
    public Integer getPort() { return port; }
    public void setPort(Integer port) { this.port = port; }
    public String getInstallDir() { return installDir; }
    public void setInstallDir(String installDir) { this.installDir = installDir; }
    public String getPackageName() { return packageName; }
    public void setPackageName(String packageName) { this.packageName = packageName; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public LocalDateTime getInstalledAt() { return installedAt; }
    public void setInstalledAt(LocalDateTime installedAt) { this.installedAt = installedAt; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }

    @PrePersist
    protected void onCreate() {
        if (installedAt == null) installedAt = LocalDateTime.now();
        updateTime = LocalDateTime.now();
        if (status == null) status = "UNKNOWN";
    }

    @PreUpdate
    protected void onUpdate() { updateTime = LocalDateTime.now(); }
}