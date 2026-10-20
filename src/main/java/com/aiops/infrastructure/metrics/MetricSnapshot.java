package com.aiops.infrastructure.metrics;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 实时指标快照（用于告警阈值判断）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MetricSnapshot {
    private double cpuUsage;
    private double memUsage;
    private double diskUsage;
    private String status;
}