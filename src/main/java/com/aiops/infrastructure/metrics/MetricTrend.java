package com.aiops.infrastructure.metrics;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 时序数据集合（统一后端返回结构，前端ECharts不改代码）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MetricTrend {
    private List<String> times;
    private List<Double> values;
    private String metricName;
    private Long serverId;
}