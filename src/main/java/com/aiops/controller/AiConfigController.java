package com.aiops.controller;

import com.aiops.common.Result;
import com.aiops.infrastructure.ai.AiChatClient;
import com.aiops.infrastructure.ai.AIClientFactory;
import com.aiops.service.ConfigService;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 配置热更新：保存即生效（写 sys_config + 刷新工厂 + 立即健康检测），无需重启。
 */
@RestController
@RequestMapping("/api/system/config")
public class AiConfigController {

    private final ConfigService config;
    private final AIClientFactory factory;

    public AiConfigController(ConfigService config, AIClientFactory factory) {
        this.config = config; this.factory = factory;
    }

    /** 读取当前 AI 配置（回填表单） */
    @GetMapping("/ai")
    public Result<Map<String, String>> get() {
        Map<String, String> out = new LinkedHashMap<String, String>();
        out.put("provider", config.getString("ai.provider", "none"));
        out.put("apiKey", config.getString("ai.api-key", ""));
        out.put("apiUrl", config.getString("ai.apiUrl", ""));
        out.put("model", config.getString("ai.model", ""));
        out.put("hiagentBaseUrl", config.getString("ai.hiagent.base-url", ""));
        out.put("hiagentAppId", config.getString("ai.hiagent.app-id", ""));
        out.put("hiagentVisitorKey", config.getString("ai.hiagent.visitor-key", ""));
        out.put("hiagentCookie", config.getString("ai.hiagent.cookie", ""));
        out.put("hiagentCsrf", config.getString("ai.hiagent.csrf", ""));
        return Result.success(out);
    }

    /** 保存 AI 配置 → 立即刷新 → 健康检测 */
    @PostMapping("/ai")
    public Result<Map<String, Object>> save(@RequestBody Map<String, String> body) {
        Map<String, String> kv = new HashMap<String, String>();
        putIfPresent(kv, body, "provider", "ai.provider");
        putIfPresent(kv, body, "apiKey", "ai.api-key");
        putIfPresent(kv, body, "apiUrl", "ai.apiUrl");
        putIfPresent(kv, body, "model", "ai.model");
        putIfPresent(kv, body, "hiagentBaseUrl", "ai.hiagent.base-url");
        putIfPresent(kv, body, "hiagentAppId", "ai.hiagent.app-id");
        putIfPresent(kv, body, "hiagentVisitorKey", "ai.hiagent.visitor-key");
        putIfPresent(kv, body, "hiagentCookie", "ai.hiagent.cookie");
        putIfPresent(kv, body, "hiagentCsrf", "ai.hiagent.csrf");
        config.setMany(kv);
        factory.refresh();

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("saved", true);
        out.put("provider", config.getString("ai.provider", "none"));
        boolean reachable = false; String err = null;
        try { reachable = factory.healthCheck(); }
        catch (Exception e) { err = e.getMessage(); }
        out.put("aiReachable", reachable);
        if (err != null) out.put("error", err);
        out.put("message", reachable ? "配置已保存并生效，AI 服务正常" : "配置已保存并生效，但 AI 服务当前不可达（已降级本地规则）");
        return Result.success(out);
    }

    /** 独立测试连接（发一条消息） */
    @PostMapping("/ai/test")
    public Result<Map<String, Object>> test(@RequestBody(required = false) Map<String, String> body) {
        String msg = body != null && body.get("message") != null && !body.get("message").trim().isEmpty()
                ? body.get("message") : "你好";
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        AiChatClient c = factory.getActive();
        if (c == null) {
            out.put("success", false); out.put("mode", "RULE");
            out.put("message", "未启用大模型（provider=none 或已降级），AI 助手使用本地规则模式");
            return Result.success(out);
        }
        long t0 = System.currentTimeMillis();
        try {
            List<Map<String, String>> history = new ArrayList<Map<String, String>>();
            Map<String, String> um = new HashMap<String, String>();
            um.put("role", "user"); um.put("content", msg); history.add(um);
            String reply = c.chat("你是运维助手，简洁回答。", history);
            out.put("success", reply != null && !reply.isEmpty());
            out.put("elapsedMs", System.currentTimeMillis() - t0);
            out.put("reply", reply == null ? "(空)" : (reply.length() > 500 ? reply.substring(0, 500) : reply));
        } catch (Exception e) {
            out.put("success", false);
            out.put("message", "测试失败: " + e.getMessage());
        }
        return Result.success(out);
    }

    /** 状态回显 */
    @GetMapping("/ai/status")
    public Result<Map<String, Object>> status() {
        return Result.success(factory.info());
    }

    private void putIfPresent(Map<String, String> to, Map<String, String> from, String field, String key) {
        if (from != null && from.containsKey(field)) to.put(key, from.get(field));
    }
}