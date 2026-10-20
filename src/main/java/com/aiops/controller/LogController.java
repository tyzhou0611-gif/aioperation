package com.aiops.controller;

import com.aiops.common.PageResult;
import com.aiops.common.Result;
import com.aiops.infrastructure.logs.LogProvider;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/log")
public class LogController {
    private final LogProvider logProvider;
    public LogController(LogProvider logProvider) { this.logProvider = logProvider; }

    @GetMapping("/search")
    public Result<PageResult<Map<String, Object>>> search(
            @RequestParam(required = false) Long serverId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        LocalDateTime start = startTime != null ? LocalDateTime.parse(startTime.replace("T"," ").substring(0,19)) : null;
        LocalDateTime end = endTime != null ? LocalDateTime.parse(endTime.replace("T"," ").substring(0,19)) : null;
        return Result.success(logProvider.searchLogs(serverId, keyword, start, end, page, size));
    }
}