package com.aiops.controller;

import com.aiops.common.Result;
import com.aiops.entity.SysConfig;
import com.aiops.repository.SysConfigRepository;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {
    private final SysConfigRepository repository;
    public SettingsController(SysConfigRepository r) { this.repository = r; }

    @GetMapping
    public Result<List<SysConfig>> getAll() { return Result.success(repository.findAll()); }

    @PutMapping
    public Result<Void> update(@RequestBody SysConfig config) {
        SysConfig existing = repository.findByConfigKey(config.getConfigKey());
        if (existing != null) {
            existing.setConfigValue(config.getConfigValue());
            repository.save(existing);
        } else {
            repository.save(config);
        }
        return Result.success("更新成功", null);
    }

    @PostMapping("/test-connection")
    public Result<Map<String, Object>> testConnection(@RequestBody Map<String, String> req) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("type", req.getOrDefault("type", ""));
        r.put("url", req.getOrDefault("url", ""));
        r.put("reachable", true);
        r.put("message", "连接测试返回占位结果（实际网络连接需部署后验证）");
        return Result.success(r);
    }
}