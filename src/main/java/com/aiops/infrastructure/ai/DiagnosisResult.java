package com.aiops.infrastructure.ai;

public class DiagnosisResult {
    private String diagnosis;
    private String suggestion;
    private double confidence;

    public DiagnosisResult(String diagnosis, String suggestion, double confidence) {
        this.diagnosis = diagnosis; this.suggestion = suggestion; this.confidence = confidence;
    }
    public String getDiagnosis() { return diagnosis; }
    public String getSuggestion() { return suggestion; }
    public double getConfidence() { return confidence; }
}