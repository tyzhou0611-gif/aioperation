package com.aiops.entity;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity @Table(name = "ai_diagnosis_history")
public class DiagnosisHistory {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "alert_id") private Long alertId;
    @Column(name = "server_id") private Long serverId;
    @Column(name = "server_ip", length = 45) private String serverIp;
    @Column(name = "diagnosis_type", length = 20) private String diagnosisType;
    @Column(name = "input_data", columnDefinition = "TEXT") private String inputData;
    @Column(name = "diagnosis_result", columnDefinition = "TEXT") private String diagnosisResult;
    @Column(columnDefinition = "TEXT") private String suggestion;
    private Double confidence;
    @Column(updatable = false) private LocalDateTime createTime;

    public DiagnosisHistory() {}
    public Long getId() { return id; }
    public Long getAlertId() { return alertId; } public void setAlertId(Long v) { this.alertId = v; }
    public Long getServerId() { return serverId; } public void setServerId(Long v) { this.serverId = v; }
    public String getServerIp() { return serverIp; } public void setServerIp(String v) { this.serverIp = v; }
    public String getDiagnosisType() { return diagnosisType; } public void setDiagnosisType(String v) { this.diagnosisType = v; }
    public String getInputData() { return inputData; } public void setInputData(String v) { this.inputData = v; }
    public String getDiagnosisResult() { return diagnosisResult; } public void setDiagnosisResult(String v) { this.diagnosisResult = v; }
    public String getSuggestion() { return suggestion; } public void setSuggestion(String v) { this.suggestion = v; }
    public Double getConfidence() { return confidence; } public void setConfidence(Double v) { this.confidence = v; }
    public LocalDateTime getCreateTime() { return createTime; }
    @PrePersist protected void onCreate() { createTime = LocalDateTime.now(); }
}