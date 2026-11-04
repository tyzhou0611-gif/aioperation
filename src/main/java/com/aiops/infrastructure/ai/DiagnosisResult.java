package com.aiops.infrastructure.ai;

public class DiagnosisResult {
    private String diagnosis;
    private String suggestion;
    private double confidence;
    private String diagnosisType;   // LLM / RULE
    private String model;           // 实际使用的模型（规则引擎为 null）
    private long elapsedMs;         // 诊断耗时

    public DiagnosisResult(String diagnosis, String suggestion, double confidence) {
        this(diagnosis, suggestion, confidence, "RULE", null, 0L);
    }

    public DiagnosisResult(String diagnosis, String suggestion, double confidence,
                           String diagnosisType, String model, long elapsedMs) {
        this.diagnosis = diagnosis; this.suggestion = suggestion; this.confidence = confidence;
        this.diagnosisType = diagnosisType; this.model = model; this.elapsedMs = elapsedMs;
    }

    public String getDiagnosis() { return diagnosis; }
    public String getSuggestion() { return suggestion; }
    public double getConfidence() { return confidence; }
    public String getDiagnosisType() { return diagnosisType; }
    public String getModel() { return model; }
    public long getElapsedMs() { return elapsedMs; }
}