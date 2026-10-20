package com.aiops.infrastructure.metrics;

import java.util.Collections;
import java.util.List;

/**
 * 可插拔指标提供者接口
 *
 * 定义统一的指标采集抽象层，支持多种实现：
 * - {@link DefaultMetricsProvider}  本地模拟（默认）
 * - {@link PrometheusMetricsProvider}  外接 Prometheus
 *
 * 所有方法均支持按 serverId 隔离采集，
 * 上层（DashboardService、AlertCheckTask）通过此接口获取数据，与具体数据源解耦。
 */
public interface MetricsProvider {

    /**
     * 获取指定服务器的实时 CPU 使用率（百分比 0~100）
     */
    double getCpuUsage(Long serverId);

    /**
     * 获取指定服务器的实时内存使用率（百分比 0~100）
     */
    double getMemoryUsage(Long serverId);

    /**
     * 获取指定服务器的实时磁盘使用率（百分比 0~100）
     */
    double getDiskUsage(Long serverId);

    /**
     * 获取指定服务器的运行状态 ONLINE / OFFLINE / MAINTENANCE
     */
    String getServerStatus(Long serverId);

    /**
     * 获取指定服务器过去 N 小时的 CPU 使用率趋势
     */
    MetricTrend getCpuUsageTrend(Long serverId, int hours);

    /**
     * 获取指定服务器过去 N 小时的内存使用率趋势
     */
    MetricTrend getMemoryUsageTrend(Long serverId, int hours);

    /**
     * 发现当前数据源监控到的全部节点实例（用于"从 Prometheus 同步服务器"）。
     * 默认实现返回空（本地模拟源无从外部发现节点）。
     */
    default List<NodeInstance> discoverNodeInstances() {
        return Collections.emptyList();
    }

    /**
     * 获取指定指标的数值（通过 metricName 路由，用于告警规则通用判断）
     */
    default double getMetricValue(String metricName, Long serverId) {
        if ("CPU_USAGE".equals(metricName)) return getCpuUsage(serverId);
        if ("MEM_USAGE".equals(metricName)) return getMemoryUsage(serverId);
        if ("DISK_USAGE".equals(metricName)) return getDiskUsage(serverId);
        return 0.0;
    }
}