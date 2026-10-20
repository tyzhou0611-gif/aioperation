package com.aiops.infrastructure.metrics;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 单个时序数据点
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MetricPoint {
    private String time;
    private double value;
}