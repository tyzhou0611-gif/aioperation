package com.aiops.entity;

import javax.persistence.*;
import java.time.LocalDateTime;

/** 项目可见角色（多对多：项目 -> 角色） */
@Entity
@Table(name = "project_role",
        uniqueConstraints = @UniqueConstraint(name = "uk_project_role",
                columnNames = {"project_id", "role_id"}))
public class ProjectRole {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "project_id", nullable = false) private Long projectId;
    @Column(name = "role_id", nullable = false) private Long roleId;
    @Column(name = "create_time", updatable = false) private LocalDateTime createTime;

    public ProjectRole() {}
    public ProjectRole(Long projectId, Long roleId) { this.projectId = projectId; this.roleId = roleId; }

    public Long getId() { return id; } public void setId(Long v) { id = v; }
    public Long getProjectId() { return projectId; } public void setProjectId(Long v) { projectId = v; }
    public Long getRoleId() { return roleId; } public void setRoleId(Long v) { roleId = v; }
    public LocalDateTime getCreateTime() { return createTime; }

    @PrePersist protected void onCreate() { createTime = LocalDateTime.now(); }
}