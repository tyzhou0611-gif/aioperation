package com.aiops.entity;

import javax.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * 系统角色实体
 */
@Entity
@Table(name = "sys_role")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SysRole {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String name;

    /** 中文角色名称，例如“超级管理员” */
    @Column(length = 50)
    private String label;

    @Column(length = 200)
    private String description;

    /** 标签颜色 #RRGGBB */
    @Column(name = "tag_color", length = 20)
    private String tagColor;

    /** 1-内置角色（不可删除），0-自定义角色 */
    @Column(name = "builtin")
    private Integer builtin;

    /** 数据范围：GLOBAL / TENANT / PROJECT / READONLY */
    @Column(length = 20)
    private String scope;

    @Builder.Default
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "sys_role_menu",
            joinColumns = @JoinColumn(name = "role_id"),
            inverseJoinColumns = @JoinColumn(name = "menu_id"))
    private Set<SysMenu> menus = new HashSet<>();

    @Column(updatable = false)
    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @PrePersist
    protected void onCreate() {
        createTime = LocalDateTime.now();
        updateTime = LocalDateTime.now();
        if (builtin == null) builtin = 0;
        if (scope == null) scope = "GLOBAL";
    }

    @PreUpdate
    protected void onUpdate() {
        updateTime = LocalDateTime.now();
    }
}