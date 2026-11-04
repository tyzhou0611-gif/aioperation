package com.aiops.controller;

import com.aiops.common.Result;
import com.aiops.service.MonitorPanelConfigService;
import com.aiops.service.MonitorService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * 原生监控面板 API（数据来自 Prometheus，不再依赖 Grafana 登录）
 */
@RestController
@RequestMapping("/api/monitor")
@RequiredArgsConstructor
public class MonitorController {

    private final MonitorService monitorService;
    private final MonitorPanelConfigService panelConfigService;

    @GetMapping("/overview")
    public Result<Map<String, Object>> overview(
            @RequestParam(defaultValue = "1h") String range,
            @RequestParam(defaultValue = "all") String instance,
            @RequestParam(required = false) Long start,
            @RequestParam(required = false) Long end) {
        if ((start == null) != (end == null)) {
            return Result.error("自定义时间需要同时提供开始时间和结束时间");
        }
        if (start != null) {
            long s = Math.min(start, end);
            long e = Math.max(start, end);
            long span = e - s;
            if (span < 60) return Result.error("查询时间范围不能小于 1 分钟");
            if (span > 31L * 24 * 3600) return Result.error("自定义时间范围不能超过 31 天");
            long now = Instant.now().getEpochSecond();
            if (e > now + 60) return Result.error("结束时间不能晚于当前时间");
            range = "custom";
        }
        return Result.success(monitorService.overview(range, instance, start, end));
    }

    /** 组件显隐 / 自定义重点指标配置 */
    @GetMapping("/panel-config")
    public Result<Map<String, Object>> panelConfig() {
        return Result.success(panelConfigService.get());
    }

    @PutMapping("/panel-config")
    public Result<Map<String, Object>> savePanelConfig(@RequestBody Map<String, Object> body) {
        try {
            return Result.success("监控面板配置已保存", panelConfigService.save(body));
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        } catch (Exception e) {
            return Result.error(500, "保存失败: " + e.getMessage());
        }
    }

    /** 自定义指标“试运行”：返回时间序列 + 最新值，便于开发调试 PromQL */
    @PostMapping("/metric-test")
    public Result<Map<String, Object>> metricTest(@RequestBody Map<String, Object> body) {
        String query = body.get("query") == null ? "" : String.valueOf(body.get("query"));
        String unit = body.get("unit") == null ? "num" : String.valueOf(body.get("unit"));
        String instance = body.get("instance") == null ? "all" : String.valueOf(body.get("instance"));
        String range = body.get("range") == null ? "1h" : String.valueOf(body.get("range"));
        Long start = parseLong(body.get("start"));
        Long end = parseLong(body.get("end"));
        if (start == null || end == null) { start = null; end = null; }
        return Result.success(monitorService.testMetric(query, unit, instance, range, start, end));
    }

    private Long parseLong(Object v) {
        if (v == null) return null;
        try { return Long.parseLong(String.valueOf(v).trim()); } catch (Exception e) { return null; }
    }
}