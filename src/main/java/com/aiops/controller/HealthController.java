package com.aiops.controller;

import com.aiops.common.Result;
import com.aiops.infrastructure.ai.AIClientFactory;
import com.aiops.infrastructure.logs.LogProvider;
import com.aiops.infrastructure.metrics.MetricsProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/system")
public class HealthController {
    private final MetricsProvider metricsProvider;
    private final LogProvider logProvider;
    private final AIClientFactory aiFactory;
    private final DataSource dataSource;

    public HealthController(MetricsProvider m, LogProvider l, AIClientFactory aiFactory, DataSource dataSource) {
        this.metricsProvider = m; this.logProvider = l; this.aiFactory = aiFactory; this.dataSource = dataSource;
    }

    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("status", "UP");
        h.put("metricsSource", metricsProvider.getClass().getSimpleName());
        h.put("logSource", logProvider.getClass().getSimpleName());
        h.put("database", databaseInfo());
        Map<String, Object> ai = aiFactory.info();
        h.put("ai", ai);
        h.put("aiService", ai.get("provider") + " reachable=" + ai.get("reachable")
                + " fallback=" + ai.get("fallbackActive"));
        h.put("timestamp", LocalDateTime.now().toString());
        return Result.success(h);
    }

    /** 返回真实连接的数据源类型/版本/库名，避免健康检查写死 H2 */
    private String databaseInfo() {
        try (Connection c = dataSource.getConnection()) {
            DatabaseMetaData m = c.getMetaData();
            String catalog = c.getCatalog();
            return m.getDatabaseProductName() + " " + m.getDatabaseProductVersion()
                    + (catalog == null || catalog.isEmpty() ? "" : " / " + catalog);
        } catch (Exception e) {
            return "unavailable: " + e.getMessage();
        }
    }
}