package com.aiops.entity;

import com.fasterxml.jackson.annotation.JsonProperty;

import javax.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 系统用户实体
 */
@Entity
@Table(name = "sys_user")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SysUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String username;

    @Column(nullable = false, length = 200)
    private String password;

    @Column(length = 100)
    private String email;

    @Column(length = 50)
    private String nickname;

    @Column(length = 500)
    private String avatar;

    /** 0-正常 1-停用 */
    private Integer status;

    /** 仪表盘界面风格：tech(科技风) / normal(普通版) */
    @Column(name = "dashboard_style", length = 20)
    private String dashboardStyle;

    @Builder.Default
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "sys_user_role",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<SysRole> roles = new HashSet<>();

    /** 入参：分配角色的 ID 列表（仅超级管理员可写） */
    @Transient
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private List<Long> roleIds;

    @Column(updatable = false)
    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @PrePersist
    protected void onCreate() {
        createTime = LocalDateTime.now();
        updateTime = LocalDateTime.now();
        if (status == null) status = 0;
        if (dashboardStyle == null) dashboardStyle = "tech";
    }

    @PreUpdate
    protected void onUpdate() {
        updateTime = LocalDateTime.now();
    }
}