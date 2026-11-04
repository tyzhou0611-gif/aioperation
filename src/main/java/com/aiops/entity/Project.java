package com.aiops.entity;

import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * 项目：大屏按项目区分机器与链路。
 * 项目与资产（服务器/中间件/应用）是多对多关系，见 {@link ProjectAsset}。
 */
@Entity
@Table(name = "project",
        uniqueConstraints = @UniqueConstraint(name = "uk_project_code", columnNames = "code"))
public class Project {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 100) private String name;
    @Column(nullable = false, unique = true, length = 50) private String code;
    @Column(length = 50) private String owner;
    @Column(length = 500) private String description;
    @Column(length = 20) private String status;
    @Column(name = "create_time", updatable = false) private LocalDateTime createTime;
    @Column(name = "update_time") private LocalDateTime updateTime;

    public Project() {}

    public Long getId() { return id; } public void setId(Long v) { id = v; }
    public String getName() { return name; } public void setName(String v) { name = v; }
    public String getCode() { return code; } public void setCode(String v) { code = v; }
    public String getOwner() { return owner; } public void setOwner(String v) { owner = v; }
    public String getDescription() { return description; } public void setDescription(String v) { description = v; }
    public String getStatus() { return status; } public void setStatus(String v) { status = v; }
    public LocalDateTime getCreateTime() { return createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }

    @PrePersist protected void onCreate() {
        createTime = LocalDateTime.now();
        updateTime = LocalDateTime.now();
        if (status == null || status.trim().isEmpty()) status = "ACTIVE";
    }
    @PreUpdate protected void onUpdate() { updateTime = LocalDateTime.now(); }
}