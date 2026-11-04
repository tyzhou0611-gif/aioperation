package com.aiops.entity;

import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * 命令执行记录
 */
@Entity
@Table(name = "exec_log")
public class ExecLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(length = 50) private String operator;
    @Column(name = "target_type", length = 20) private String targetType;
    @Column(name = "target_id") private Long targetId;
    @Column(name = "target_ip", length = 45) private String targetIp;
    @Column(length = 1000) private String command;
    @Lob private String result;
    private Integer success;
    @Column(name = "create_time", updatable = false) private LocalDateTime createTime;

    public ExecLog() {}

    public Long getId() { return id; } public void setId(Long v) { id = v; }
    public String getOperator() { return operator; } public void setOperator(String v) { operator = v; }
    public String getTargetType() { return targetType; } public void setTargetType(String v) { targetType = v; }
    public Long getTargetId() { return targetId; } public void setTargetId(Long v) { targetId = v; }
    public String getTargetIp() { return targetIp; } public void setTargetIp(String v) { targetIp = v; }
    public String getCommand() { return command; } public void setCommand(String v) { command = v; }
    public String getResult() { return result; } public void setResult(String v) { result = v; }
    public Integer getSuccess() { return success; } public void setSuccess(Integer v) { success = v; }
    public LocalDateTime getCreateTime() { return createTime; }

    @PrePersist protected void onCreate() { createTime = LocalDateTime.now(); }
}