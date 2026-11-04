package com.aiops.entity;

import javax.persistence.*;
import java.time.LocalDateTime;

/** 一次命令执行（批量）记录 */
@Entity
@Table(name = "exec_run")
public class ExecRun {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "request_id", length = 64) private String requestId;
    @Column(length = 50) private String operator;
    @Lob private String command;
    @Column(name = "server_count") private Integer serverCount;
    @Column(name = "success_count") private Integer successCount;
    @Column(name = "fail_count") private Integer failCount;
    @Column(name = "dry_run") private Integer dryRun;
    @Column(name = "risk_level", length = 20) private String riskLevel;
    @Column(length = 20) private String status;
    @Column(name = "start_time") private LocalDateTime startTime;
    @Column(name = "end_time") private LocalDateTime endTime;
    @Column(name = "duration_ms") private Long durationMs;
    @Column(name = "create_time", updatable = false) private LocalDateTime createTime;

    public ExecRun() {}

    public Long getId() { return id; } public void setId(Long v) { id = v; }
    public String getRequestId() { return requestId; } public void setRequestId(String v) { requestId = v; }
    public String getOperator() { return operator; } public void setOperator(String v) { operator = v; }
    public String getCommand() { return command; } public void setCommand(String v) { command = v; }
    public Integer getServerCount() { return serverCount; } public void setServerCount(Integer v) { serverCount = v; }
    public Integer getSuccessCount() { return successCount; } public void setSuccessCount(Integer v) { successCount = v; }
    public Integer getFailCount() { return failCount; } public void setFailCount(Integer v) { failCount = v; }
    public Integer getDryRun() { return dryRun; } public void setDryRun(Integer v) { dryRun = v; }
    public String getRiskLevel() { return riskLevel; } public void setRiskLevel(String v) { riskLevel = v; }
    public String getStatus() { return status; } public void setStatus(String v) { status = v; }
    public LocalDateTime getStartTime() { return startTime; } public void setStartTime(LocalDateTime v) { startTime = v; }
    public LocalDateTime getEndTime() { return endTime; } public void setEndTime(LocalDateTime v) { endTime = v; }
    public Long getDurationMs() { return durationMs; } public void setDurationMs(Long v) { durationMs = v; }
    public LocalDateTime getCreateTime() { return createTime; }

    @PrePersist protected void onCreate() {
        createTime = LocalDateTime.now();
        if (startTime == null) startTime = createTime;
        if (status == null) status = "RUNNING";
        if (serverCount == null) serverCount = 0;
        if (successCount == null) successCount = 0;
        if (failCount == null) failCount = 0;
        if (dryRun == null) dryRun = 0;
    }
}