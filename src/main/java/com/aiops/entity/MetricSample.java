package com.aiops.entity;

import javax.persistence.*;
import java.time.LocalDateTime;

/** 外部推送到指标注册中心的样本（source_type=push） */
@Entity
@Table(name = "metric_sample")
public class MetricSample {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "metric_code", nullable = false, length = 100)
    private String metricCode;

    @Column(name = "labels_json", length = 1000)
    private String labelsJson;

    @Column(name = "metric_value", nullable = false)
    private Double value;

    @Column(name = "sample_time", nullable = false)
    private Long sampleTime;

    @Column(updatable = false)
    private LocalDateTime createTime;

    public MetricSample() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getMetricCode() { return metricCode; }
    public void setMetricCode(String metricCode) { this.metricCode = metricCode; }
    public String getLabelsJson() { return labelsJson; }
    public void setLabelsJson(String labelsJson) { this.labelsJson = labelsJson; }
    public Double getValue() { return value; }
    public void setValue(Double value) { this.value = value; }
    public Long getSampleTime() { return sampleTime; }
    public void setSampleTime(Long sampleTime) { this.sampleTime = sampleTime; }
    public LocalDateTime getCreateTime() { return createTime; }

    @PrePersist
    protected void onCreate() { createTime = LocalDateTime.now(); }
}