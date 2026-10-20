package com.aiops.entity;

import javax.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 系统菜单实体
 */
@Entity
@Table(name = "sys_menu")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SysMenu {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "parent_id")
    private Long parentId;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(length = 200)
    private String path;

    @Column(length = 200)
    private String component;

    @Column(length = 100)
    private String icon;

    private Integer sort;

    @Column(length = 100)
    private String permission;

    /** 0-目录 1-菜单 2-按钮 */
    private Integer type;

    @Column(updatable = false)
    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @PrePersist
    protected void onCreate() {
        createTime = LocalDateTime.now();
        updateTime = LocalDateTime.now();
        if (parentId == null) parentId = 0L;
        if (sort == null) sort = 0;
        if (type == null) type = 1;
    }

    @PreUpdate
    protected void onUpdate() {
        updateTime = LocalDateTime.now();
    }
}