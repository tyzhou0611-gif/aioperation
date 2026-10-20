package com.aiops.entity;

import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * 中间件资产（数据库/消息队列/缓存等）
 */
@Entity
@Table(name = "middleware_asset")
public class MiddlewareAsset {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 100) private String name;
    @Column(nullable = false, length = 30) private String type;
    @Column(length = 50) private String version;
    @Column(length = 45) private String ip;
    private Integer port;
    @Column(name = "cluster_name", length = 100) private String clusterName;
    @Column(length = 20) private String env;
    @Column(length = 20) private String status;
    @Column(name = "server_id") private Long serverId;
    @Lob @Column(name = "app_ids") private String appIds;
    @Column(length = 500) private String tags;
    @Column(name = "exporter_url", length = 500) private String exporterUrl;
    @Column(name = "prometheus_job_name", length = 100) private String prometheusJobName;
    @Column(name = "monitor_enabled") private Integer monitorEnabled;
    @Column(name = "create_time", updatable = false) private LocalDateTime createTime;
    @Column(name = "update_time") private LocalDateTime updateTime;

    public MiddlewareAsset() {}

    public Long getId() { return id; } public void setId(Long v) { id = v; }
    public String getName() { return name; } public void setName(String v) { name = v; }
    public String getType() { return type; } public void setType(String v) { type = v; }
    public String getVersion() { return version; } public void setVersion(String v) { version = v; }
    public String getIp() { return ip; } public void setIp(String v) { ip = v; }
    public Integer getPort() { return port; } public void setPort(Integer v) { port = v; }
    public String getClusterName() { return clusterName; } public void setClusterName(String v) { clusterName = v; }
    public String getEnv() { return env; } public void setEnv(String v) { env = v; }
    public String getStatus() { return status; } public void setStatus(String v) { status = v; }
    public Long getServerId() { return serverId; } public void setServerId(Long v) { serverId = v; }
    public String getAppIds() { return appIds; } public void setAppIds(String v) { appIds = v; }
    public String getTags() { return tags; } public void setTags(String v) { tags = v; }
    public String getExporterUrl() { return exporterUrl; } public void setExporterUrl(String v) { exporterUrl = v; }
    public String getPrometheusJobName() { return prometheusJobName; } public void setPrometheusJobName(String v) { prometheusJobName = v; }
    public Integer getMonitorEnabled() { return monitorEnabled; } public void setMonitorEnabled(Integer v) { monitorEnabled = v; }
    public LocalDateTime getCreateTime() { return createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }

    @PrePersist protected void onCreate() {
        createTime = LocalDateTime.now(); updateTime = LocalDateTime.now();
        if (status == null) status = "UNKNOWN";
        if (env == null) env = "production";
    }
    @PreUpdate protected void onUpdate() { updateTime = LocalDateTime.now(); }
}