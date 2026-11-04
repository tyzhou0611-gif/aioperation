package com.aiops.entity;

import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * 项目-资产关联（多对多）。
 * assetType 取值：SERVER / MIDDLEWARE / APP
 */
@Entity
@Table(name = "project_asset",
        uniqueConstraints = @UniqueConstraint(name = "uk_project_asset",
                columnNames = {"project_id", "asset_type", "asset_id"}))
public class ProjectAsset {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "project_id", nullable = false) private Long projectId;
    @Column(name = "asset_type", nullable = false, length = 20) private String assetType;
    @Column(name = "asset_id", nullable = false) private Long assetId;
    @Column(name = "create_time", updatable = false) private LocalDateTime createTime;

    public ProjectAsset() {}

    public ProjectAsset(Long projectId, String assetType, Long assetId) {
        this.projectId = projectId;
        this.assetType = assetType;
        this.assetId = assetId;
    }

    public Long getId() { return id; } public void setId(Long v) { id = v; }
    public Long getProjectId() { return projectId; } public void setProjectId(Long v) { projectId = v; }
    public String getAssetType() { return assetType; } public void setAssetType(String v) { assetType = v; }
    public Long getAssetId() { return assetId; } public void setAssetId(Long v) { assetId = v; }
    public LocalDateTime getCreateTime() { return createTime; }

    @PrePersist protected void onCreate() { createTime = LocalDateTime.now(); }
}