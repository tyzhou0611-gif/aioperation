package com.aiops.controller;

import com.aiops.common.Result;
import com.aiops.infrastructure.ai.AIClientFactory;
import com.aiops.infrastructure.logs.LogProvider;
import com.aiops.infrastructure.metrics.MetricsProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/system")
public class HealthController {
    private final MetricsProvider metricsProvider;
    private final LogProvider logProvider;
    private final AIClientFactory aiFactory;

    public HealthController(MetricsProvider m, LogProvider l, AIClientFactory aiFactory) {
        this.metricsProvider = m; this.logProvider = l; this.aiFactory = aiFactory;
    }

    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("status", "UP");
        h.put("metricsSource", metricsProvider.getClass().getSimpleName());
        h.put("logSource", logProvider.getClass().getSimpleName());
        h.put("database", "H2 file:./data/aiops");
        Map<String, Object> ai = aiFactory.info();
        h.put("ai", ai);
        h.put("aiService", ai.get("provider") + " reachable=" + ai.get("reachable")
                + " fallback=" + ai.get("fallbackActive"));
        h.put("timestamp", LocalDateTime.now().toString());
        return Result.success(h);
    }
}