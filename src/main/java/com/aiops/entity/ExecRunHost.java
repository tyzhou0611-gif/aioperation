package com.aiops.entity;

import javax.persistence.*;
import java.time.LocalDateTime;

/** 一次批量执行中单台主机的记录 */
@Entity
@Table(name = "exec_run_host")
public class ExecRunHost {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "run_id", nullable = false) private Long runId;
    @Column(name = "server_id") private Long serverId;
    @Column(length = 100) private String hostname;
    @Column(length = 45) private String ip;
    @Column(length = 20) private String environment;
    @Column(name = "exit_code") private Integer exitCode;
    private Integer success;
    @Column(name = "duration_ms") private Long durationMs;
    @Lob private String stdout;
    @Lob private String stderr;
    @Column(name = "error_msg", length = 1000) private String errorMsg;
    private Integer killed;
    @Column(name = "create_time", updatable = false) private LocalDateTime createTime;

    public ExecRunHost() {}

    public Long getId() { return id; } public void setId(Long v) { id = v; }
    public Long getRunId() { return runId; } public void setRunId(Long v) { runId = v; }
    public Long getServerId() { return serverId; } public void setServerId(Long v) { serverId = v; }
    public String getHostname() { return hostname; } public void setHostname(String v) { hostname = v; }
    public String getIp() { return ip; } public void setIp(String v) { ip = v; }
    public String getEnvironment() { return environment; } public void setEnvironment(String v) { environment = v; }
    public Integer getExitCode() { return exitCode; } public void setExitCode(Integer v) { exitCode = v; }
    public Integer getSuccess() { return success; } public void setSuccess(Integer v) { success = v; }
    public Long getDurationMs() { return durationMs; } public void setDurationMs(Long v) { durationMs = v; }
    public String getStdout() { return stdout; } public void setStdout(String v) { stdout = v; }
    public String getStderr() { return stderr; } public void setStderr(String v) { stderr = v; }
    public String getErrorMsg() { return errorMsg; } public void setErrorMsg(String v) { errorMsg = v; }
    public Integer getKilled() { return killed; } public void setKilled(Integer v) { killed = v; }
    public LocalDateTime getCreateTime() { return createTime; }

    @PrePersist protected void onCreate() {
        createTime = LocalDateTime.now();
        if (killed == null) killed = 0;
    }
}