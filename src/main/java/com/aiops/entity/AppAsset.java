package com.aiops.entity;

import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * 应用资产
 */
@Entity
@Table(name = "app_asset")
public class AppAsset {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 100) private String name;
    @Column(nullable = false, unique = true, length = 100) private String code;
    @Column(length = 30) private String type;
    @Column(length = 50) private String version;
    @Column(length = 30) private String language;
    @Column(name = "deploy_type", length = 30) private String deployType;
    @Column(length = 20) private String env;
    @Column(length = 20) private String status;
    @Column(name = "server_id") private Long serverId;
    @Lob @Column(name = "depends_middleware_ids") private String dependsMiddlewareIds;
    @Lob @Column(name = "depends_app_ids") private String dependsAppIds;
    @Column(length = 50) private String owner;
    @Column(length = 500) private String tags;
    @Column(name = "create_time", updatable = false) private LocalDateTime createTime;
    @Column(name = "update_time") private LocalDateTime updateTime;

    public AppAsset() {}

    public Long getId() { return id; } public void setId(Long v) { id = v; }
    public String getName() { return name; } public void setName(String v) { name = v; }
    public String getCode() { return code; } public void setCode(String v) { code = v; }
    public String getType() { return type; } public void setType(String v) { type = v; }
    public String getVersion() { return version; } public void setVersion(String v) { version = v; }
    public String getLanguage() { return language; } public void setLanguage(String v) { language = v; }
    public String getDeployType() { return deployType; } public void setDeployType(String v) { deployType = v; }
    public String getEnv() { return env; } public void setEnv(String v) { env = v; }
    public String getStatus() { return status; } public void setStatus(String v) { status = v; }
    public Long getServerId() { return serverId; } public void setServerId(Long v) { serverId = v; }
    public String getDependsMiddlewareIds() { return dependsMiddlewareIds; } public void setDependsMiddlewareIds(String v) { dependsMiddlewareIds = v; }
    public String getDependsAppIds() { return dependsAppIds; } public void setDependsAppIds(String v) { dependsAppIds = v; }
    public String getOwner() { return owner; } public void setOwner(String v) { owner = v; }
    public String getTags() { return tags; } public void setTags(String v) { tags = v; }
    public LocalDateTime getCreateTime() { return createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }

    @PrePersist protected void onCreate() {
        createTime = LocalDateTime.now(); updateTime = LocalDateTime.now();
        if (status == null) status = "RUNNING";
        if (env == null) env = "production";
        if (type == null) type = "API";
        if (dependsMiddlewareIds == null) dependsMiddlewareIds = "[]";
        if (dependsAppIds == null) dependsAppIds = "[]";
    }
    @PreUpdate protected void onUpdate() { updateTime = LocalDateTime.now(); }
}