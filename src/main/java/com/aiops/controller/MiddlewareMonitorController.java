package com.aiops.controller;

import com.aiops.common.Result;
import com.aiops.service.MiddlewareMonitorService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 中间件实例详情页监控数据（只读）。
 * GET /api/middleware/{id}/monitor
 */
@RestController
@RequestMapping("/api/middleware")
@RequiredArgsConstructor
public class MiddlewareMonitorController {

    private final MiddlewareMonitorService monitorService;

    @GetMapping("/{id}/monitor")
    public Result<Map<String, Object>> monitor(@PathVariable Long id) {
        return Result.success(monitorService.monitor(id));
    }
}