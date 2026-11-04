package com.aiops.controller;

import com.aiops.common.Result;
import com.aiops.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 仪表盘控制器
 *
 * - GET /api/dashboard/stats  统计卡片数据
 * - GET /api/dashboard/trend  资源趋势数据（CPU/内存）
 */
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    /**
     * 仪表盘统计
     * 返回总服务器数、在线/离线数、近7天告警数
     */
    @GetMapping("/stats")
    public Result<Map<String, Object>> stats() {
        return Result.success(dashboardService.getStats());
    }

    /**
     * 资源趋势数据（过去24小时CPU/内存占用率）
     * 用于 ECharts 折线图
     *
     * @param hours 过去多少小时，默认24
     */
    @GetMapping("/trend")
    public Result<Map<String, Object>> trend(
            @RequestParam(defaultValue = "24") int hours) {
        return Result.success(dashboardService.getTrend(hours));
    }

    // ==================== 第16批：多资产总览 ====================

    /**
     * 多资产状态总览（服务器/数据库/中间件/应用四类）
     */
    @GetMapping("/multi-asset-overview")
    public Result<Map<String, Object>> multiAssetOverview() {
        return Result.success(dashboardService.getMultiAssetOverview());
    }

    /**
     * 资产健康度趋势（过去24小时，按小时采样）
     */
    @GetMapping("/asset-health-trend")
    public Result<Map<String, Object>> assetHealthTrend(
            @RequestParam(defaultValue = "24") int hours) {
        return Result.success(dashboardService.getAssetHealthTrend(hours));
    }
}