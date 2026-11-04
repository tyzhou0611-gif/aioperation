package com.aiops.entity;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity @Table(name = "db_execute_log")
public class DbExecuteLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "connection_id", nullable = false) private Long connectionId;
    @Lob @Column(name = "sql_text", nullable = false) private String sqlText;
    private Integer success;
    @Column(name = "row_count") private Integer rowCount;
    @Column(name = "elapsed_ms") private Long elapsedMs;
    @Column(name = "error_msg", length = 500) private String errorMsg;
    @Column(length = 50) private String operator;
    @Column(name = "create_time", updatable = false) private LocalDateTime createTime;
    public DbExecuteLog() {}
    public Long getId() { return id; } public void setId(Long v) { id = v; }
    public Long getConnectionId() { return connectionId; } public void setConnectionId(Long v) { connectionId = v; }
    public String getSqlText() { return sqlText; } public void setSqlText(String v) { sqlText = v; }
    public Integer getSuccess() { return success; } public void setSuccess(Integer v) { success = v; }
    public Integer getRowCount() { return rowCount; } public void setRowCount(Integer v) { rowCount = v; }
    public Long getElapsedMs() { return elapsedMs; } public void setElapsedMs(Long v) { elapsedMs = v; }
    public String getErrorMsg() { return errorMsg; } public void setErrorMsg(String v) { errorMsg = v; }
    public String getOperator() { return operator; } public void setOperator(String v) { operator = v; }
    public LocalDateTime getCreateTime() { return createTime; }
    @PrePersist protected void onCreate() { createTime = LocalDateTime.now(); }
}