package com.aiops.entity;

import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * 一键命令模板
 */
@Entity
@Table(name = "command_template")
public class CommandTemplate {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 100) private String name;
    @Column(name = "target_type", nullable = false, length = 20) private String targetType;
    @Column(name = "middleware_type", length = 30) private String middlewareType;
    @Column(nullable = false, length = 1000) private String command;
    @Column(length = 500) private String description;
    private Integer dangerous;
    @Column(name = "create_time", updatable = false) private LocalDateTime createTime;

    public CommandTemplate() {}

    public Long getId() { return id; } public void setId(Long v) { id = v; }
    public String getName() { return name; } public void setName(String v) { name = v; }
    public String getTargetType() { return targetType; } public void setTargetType(String v) { targetType = v; }
    public String getMiddlewareType() { return middlewareType; } public void setMiddlewareType(String v) { middlewareType = v; }
    public String getCommand() { return command; } public void setCommand(String v) { command = v; }
    public String getDescription() { return description; } public void setDescription(String v) { description = v; }
    public Integer getDangerous() { return dangerous; } public void setDangerous(Integer v) { dangerous = v; }
    public LocalDateTime getCreateTime() { return createTime; }

    @PrePersist protected void onCreate() {
        createTime = LocalDateTime.now();
        if (dangerous == null) dangerous = 0;
    }
}