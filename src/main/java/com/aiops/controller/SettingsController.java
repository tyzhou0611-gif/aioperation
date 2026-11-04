package com.aiops.controller;

import com.aiops.common.Result;
import com.aiops.entity.SysConfig;
import com.aiops.repository.SysConfigRepository;
import com.aiops.service.ConfigService;
import org.springframework.web.bind.annotation.*;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {
    private final SysConfigRepository repository;
    private final ConfigService configService;

    public SettingsController(SysConfigRepository r, ConfigService c) {
        this.repository = r;
        this.configService = c;
    }

    @GetMapping
    public Result<List<SysConfig>> getAll() {
        List<SysConfig> list = repository.findAll();
        for (SysConfig c : list) {
            if (ConfigService.isSecretKey(c.getConfigKey()) && c.getConfigValue() != null && !c.getConfigValue().isEmpty()) {
                c.setConfigValue("********");
            }
        }
        return Result.success(list);
    }

    /** 统一走 ConfigService 写入，保证缓存与 DB 一致、敏感项自动加密 */
    @PutMapping
    public Result<Void> update(@RequestBody SysConfig config) {
        if (config.getConfigKey() == null || config.getConfigKey().trim().isEmpty()) {
            return Result.error("configKey 不能为空");
        }
        configService.setString(config.getConfigKey(), config.getConfigValue());
        return Result.success("更新成功", null);
    }

    /** 真实连通性探测（4 秒超时；任何 HTTP 响应都视为可达） */
    @PostMapping("/test-connection")
    public Result<Map<String, Object>> testConnection(@RequestBody Map<String, String> req) {
        String type = req.getOrDefault("type", "");
        String url = req.getOrDefault("url", "").trim();
        Map<String, Object> r = new LinkedHashMap<String, Object>();
        r.put("type", type);
        r.put("url", url);
        if (url.isEmpty()) {
            r.put("reachable", false);
            r.put("message", "未填写地址");
            return Result.success(r);
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            r.put("reachable", false);
            r.put("message", "地址需以 http:// 或 https:// 开头");
            return Result.success(r);
        }
        long start = System.currentTimeMillis();
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            int code = conn.getResponseCode();
            r.put("reachable", code > 0);
            r.put("httpStatus", code);
            r.put("elapsedMs", System.currentTimeMillis() - start);
            r.put("message", "连接成功（HTTP " + code + "）");
        } catch (Exception e) {
            r.put("reachable", false);
            r.put("elapsedMs", System.currentTimeMillis() - start);
            r.put("message", "无法连接: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        } finally {
            if (conn != null) conn.disconnect();
        }
        return Result.success(r);
    }
}