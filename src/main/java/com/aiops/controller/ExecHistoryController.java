package com.aiops.controller;

import com.aiops.common.PageResult;
import com.aiops.common.Result;
import com.aiops.service.ExecHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 命令执行历史：检索、详情、CSV 导出。
 */
@RestController
@RequestMapping("/api/exec/history")
@RequiredArgsConstructor
public class ExecHistoryController {

    private final ExecHistoryService historyService;

    @GetMapping
    public Result<PageResult<Map<String, Object>>> page(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long serverId,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end) {
        return Result.success(historyService.page(page, pageSize, keyword, serverId, start, end));
    }

    @GetMapping("/{id}")
    public Result<Map<String, Object>> detail(@PathVariable Long id) {
        return Result.success(historyService.detail(id));
    }

    @GetMapping("/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable Long id) {
        String csv = historyService.exportCsv(id);
        byte[] body = ("﻿" + csv).getBytes(StandardCharsets.UTF_8);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"exec-run-" + id + ".csv\"");
        return ResponseEntity.ok().headers(headers).body(body);
    }
}