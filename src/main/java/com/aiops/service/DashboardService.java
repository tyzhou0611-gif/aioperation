package com.aiops.service;

import com.aiops.entity.ServerAsset;
import com.aiops.infrastructure.metrics.MetricsProvider;
import com.aiops.repository.AlertHistoryRepository;
import com.aiops.repository.AppAssetRepository;
import com.aiops.repository.MiddlewareAssetRepository;
import com.aiops.repository.ServerAssetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 仪表盘数据服务
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final ServerAssetRepository serverRepository;
    private final AlertHistoryRepository alertHistoryRepository;
    private final MiddlewareAssetRepository middlewareRepository;
    private final AppAssetRepository appRepository;
    private final MetricsProvider metricsProvider;

    /**
     * 仪表盘统计
     */
    public Map<String, Object> getStats() {
        List<ServerAsset> allServers = serverRepository.findAll();
        long totalServers = allServers.size();
        long onlineCount = allServers.stream().filter(s -> "ONLINE".equals(s.getStatus())).count();
        long offlineCount = allServers.stream().filter(s -> "OFFLINE".equals(s.getStatus())).count();
        long maintenanceCount = allServers.stream().filter(s -> "MAINTENANCE".equals(s.getStatus())).count();

        LocalDateTime sevenDaysAgo = LocalDateTime.now().minusDays(7);
        long alertCount7d = alertHistoryRepository.countByCreateTimeAfter(sevenDaysAgo);

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalServers", totalServers);
        stats.put("onlineCount", onlineCount);
        stats.put("offlineCount", offlineCount);
        stats.put("maintenanceCount", maintenanceCount);
        stats.put("alertCount7d", alertCount7d);
        return stats;
    }

    /**
     * 资源趋势数据（CPU/内存）
     * 通过 MetricsProvider 获取，默认模拟，可切换 Prometheus
     */
    public Map<String, Object> getTrend(int hours) {
        if (hours <= 0) hours = 24;

        // 取第一台在线服务器的趋势作为整体趋势展示
        List<ServerAsset> servers = serverRepository.findByStatus("ONLINE");
        Long targetId = servers.isEmpty() ? 0L : servers.get(0).getId();

        com.aiops.infrastructure.metrics.MetricTrend cpuTrend = metricsProvider.getCpuUsageTrend(targetId, hours);
        com.aiops.infrastructure.metrics.MetricTrend memTrend = metricsProvider.getMemoryUsageTrend(targetId, hours);

        Map<String, Object> trend = new LinkedHashMap<>();
        trend.put("times", cpuTrend.getTimes());
        trend.put("cpuUsage", cpuTrend.getValues());
        trend.put("memUsage", memTrend.getValues());
        return trend;
    }

    // ==================== 第16批：多资产总览 ====================

    private static final List<String> DB_TYPES = Arrays.asList("MYSQL", "MONGODB", "ORACLE", "DAMENG", "GREENPLUM", "GBASE", "ELASTICSEARCH");
    private static final List<String> MW_TYPES = Arrays.asList("KAFKA", "NACOS", "REDIS", "ZOOKEEPER", "RABBITMQ");

    /**
     * 多资产状态总览（服务器/数据库/中间件/应用四类）
     */
    public Map<String, Object> getMultiAssetOverview() {
        List<ServerAsset> servers = serverRepository.findAll();
        List<com.aiops.entity.MiddlewareAsset> mws = middlewareRepository.findAll();
        List<com.aiops.entity.AppAsset> apps = appRepository.findAll();

        Map<String, Object> result = new LinkedHashMap<String, Object>();

        // 服务器
        Map<String, Object> serverStats = new LinkedHashMap<String, Object>();
        serverStats.put("total", servers.size());
        serverStats.put("online", servers.stream().filter(s -> "ONLINE".equals(s.getStatus())).count());
        serverStats.put("offline", servers.stream().filter(s -> "OFFLINE".equals(s.getStatus())).count());
        serverStats.put("warning", servers.stream().filter(s -> "MAINTENANCE".equals(s.getStatus())).count());
        result.put("servers", serverStats);

        // 数据库（middleware_asset 中 DB 类型）
        List<com.aiops.entity.MiddlewareAsset> dbs = mws.stream()
                .filter(m -> DB_TYPES.contains(m.getType())).collect(java.util.stream.Collectors.toList());
        Map<String, Object> dbStats = new LinkedHashMap<String, Object>();
        dbStats.put("total", dbs.size());
        dbStats.put("online", dbs.stream().filter(m -> "ONLINE".equals(m.getStatus())).count());
        dbStats.put("offline", dbs.stream().filter(m -> "OFFLINE".equals(m.getStatus())).count());
        result.put("databases", dbStats);

        // 中间件（middleware_asset 中非 DB 类型）
        List<com.aiops.entity.MiddlewareAsset> pureMws = mws.stream()
                .filter(m -> MW_TYPES.contains(m.getType())).collect(java.util.stream.Collectors.toList());
        Map<String, Object> mwStats = new LinkedHashMap<String, Object>();
        mwStats.put("total", pureMws.size());
        mwStats.put("online", pureMws.stream().filter(m -> "ONLINE".equals(m.getStatus())).count());
        mwStats.put("offline", pureMws.stream().filter(m -> "OFFLINE".equals(m.getStatus())).count());
        result.put("middlewares", mwStats);

        // 应用
        Map<String, Object> appStats = new LinkedHashMap<String, Object>();
        appStats.put("total", apps.size());
        appStats.put("running", apps.stream().filter(a -> "RUNNING".equals(a.getStatus())).count());
        appStats.put("stopped", apps.stream().filter(a -> "STOPPED".equals(a.getStatus()) || "ERROR".equals(a.getStatus())).count());
        result.put("apps", appStats);

        // 告警按资产类型
        List<Map<String, Object>> alertsByType = new ArrayList<Map<String, Object>>();
        long serverAlerts = servers.stream().filter(s -> "OFFLINE".equals(s.getStatus())).count();
        long dbAlerts = dbs.stream().filter(m -> "OFFLINE".equals(m.getStatus())).count();
        long mwAlerts = pureMws.stream().filter(m -> "OFFLINE".equals(m.getStatus())).count();
        long appAlerts = apps.stream().filter(a -> "ERROR".equals(a.getStatus())).count();
        alertsByType.add(toAlertType("SERVER", serverAlerts));
        alertsByType.add(toAlertType("DATABASE", dbAlerts));
        alertsByType.add(toAlertType("MIDDLEWARE", mwAlerts));
        alertsByType.add(toAlertType("APP", appAlerts));
        result.put("alertsByType", alertsByType);

        return result;
    }

    private Map<String, Object> toAlertType(String type, long count) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("type", type);
        m.put("count", count);
        return m;
    }

    /**
     * 资产健康度趋势（模拟24小时，按小时采样）
     */
    public Map<String, Object> getAssetHealthTrend(int hours) {
        if (hours <= 0) hours = 24;
        Random rnd = new Random(42);
        List<ServerAsset> servers = serverRepository.findAll();
        List<com.aiops.entity.MiddlewareAsset> mws = middlewareRepository.findAll();
        List<com.aiops.entity.AppAsset> apps = appRepository.findAll();

        long serverOnline = servers.stream().filter(s -> "ONLINE".equals(s.getStatus())).count();
        long dbOnline = mws.stream().filter(m -> DB_TYPES.contains(m.getType()) && "ONLINE".equals(m.getStatus())).count();
        long mwOnline = mws.stream().filter(m -> MW_TYPES.contains(m.getType()) && "ONLINE".equals(m.getStatus())).count();
        long appRunning = apps.stream().filter(a -> "RUNNING".equals(a.getStatus())).count();

        List<String> times = new ArrayList<String>();
        List<Long> serversOnline = new ArrayList<Long>();
        List<Long> serversOffline = new ArrayList<Long>();
        List<Long> databasesOnline = new ArrayList<Long>();
        List<Long> middlewaresOnline = new ArrayList<Long>();
        List<Long> appsRunning = new ArrayList<Long>();

        LocalDateTime now = LocalDateTime.now();
        for (int i = hours - 1; i >= 0; i--) {
            times.add(String.format("%02d:00", now.minusHours(i).getHour()));
            long sOnline = Math.max(0, serverOnline - rnd.nextInt(2));
            serversOnline.add(sOnline);
            serversOffline.add(servers.size() - sOnline);
            databasesOnline.add(Math.max(0, dbOnline - rnd.nextInt(2)));
            middlewaresOnline.add(Math.max(0, mwOnline - rnd.nextInt(2)));
            appsRunning.add(Math.max(0, appRunning - rnd.nextInt(2)));
        }

        Map<String, Object> series = new LinkedHashMap<String, Object>();
        series.put("servers_online", serversOnline);
        series.put("servers_offline", serversOffline);
        series.put("databases_online", databasesOnline);
        series.put("middlewares_online", middlewaresOnline);
        series.put("apps_running", appsRunning);

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("timestamps", times);
        result.put("series", series);
        return result;
    }
}