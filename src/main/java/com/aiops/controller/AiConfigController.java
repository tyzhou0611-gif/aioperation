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

    /** 读取当前 AI 配置（敏感项不回传明文，仅返回是否已设置） */
    @GetMapping("/ai")
    public Result<Map<String, Object>> get() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("provider", config.getString("ai.provider", "none"));
        out.put("apiUrl", config.getString("ai.apiUrl", ""));
        out.put("model", config.getString("ai.model", ""));
        out.put("hiagentBaseUrl", config.getString("ai.hiagent.base-url", ""));
        out.put("hiagentAppId", config.getString("ai.hiagent.app-id", ""));
        out.put("hiagentMode", config.getString("ai.hiagent.mode", "openapi"));
        out.put("hiagentOpenapiUrl", config.getString("ai.hiagent.openapi-url", ""));
        out.put("hiagentUserId", config.getString("ai.hiagent.user-id", "aiops"));
        // 敏感项只回显是否已设置，避免明文泄露；前端“留空保持不变”
        out.put("apiKey", "");
        out.put("apiKeySet", !config.getString("ai.api-key", "").isEmpty());
        out.put("hiagentOpenapiKey", "");
        out.put("hiagentOpenapiKeySet", !config.getString("ai.hiagent.openapi-key", "").isEmpty());
        out.put("hiagentVisitorKey", "");
        out.put("hiagentVisitorKeySet", !config.getString("ai.hiagent.visitor-key", "").isEmpty());
        out.put("hiagentCookie", "");
        out.put("hiagentCookieSet", !config.getString("ai.hiagent.cookie", "").isEmpty());
        out.put("hiagentCsrf", "");
        out.put("hiagentCsrfSet", !config.getString("ai.hiagent.csrf", "").isEmpty());
        return Result.success(out);
    }

    /** 保存 AI 配置：必须先测试新配置，测试通过才落库并热切换 */
    @PostMapping("/ai")
    public Result<Map<String, Object>> save(@RequestBody Map<String, String> body) {
        Map<String, String> overrides = overrides(body);
        String provider = overrides.containsKey("ai.provider")
                ? overrides.get("ai.provider") : config.getString("ai.provider", "none");
        boolean needTest = provider != null && !provider.trim().isEmpty()
                && !"none".equalsIgnoreCase(provider.trim());

        Map<String, Object> test = null;
        boolean providerChanged = !sameProvider(body);
        if (needTest) {
            test = runTest(overrides, "你好", providerChanged);
            if (!Boolean.TRUE.equals(test.get("success"))) {
                Object err = test.get("message");
                return Result.error(400, "AI 服务测试未通过，配置未保存" + (err == null ? "" : "：" + err));
            }
        }

        Map<String, String> kv = new HashMap<String, String>();
        putIfPresent(kv, body, "provider", "ai.provider", false);
        putIfPresent(kv, body, "apiKey", "ai.api-key", true);
        putIfPresent(kv, body, "apiUrl", "ai.apiUrl", false);
        putIfPresent(kv, body, "model", "ai.model", false);
        putIfPresent(kv, body, "hiagentBaseUrl", "ai.hiagent.base-url", false);
        putIfPresent(kv, body, "hiagentAppId", "ai.hiagent.app-id", false);
        putIfPresent(kv, body, "hiagentMode", "ai.hiagent.mode", false);
        putIfPresent(kv, body, "hiagentOpenapiUrl", "ai.hiagent.openapi-url", false);
        putIfPresent(kv, body, "hiagentOpenapiKey", "ai.hiagent.openapi-key", true);
        putIfPresent(kv, body, "hiagentUserId", "ai.hiagent.user-id", false);
        putIfPresent(kv, body, "hiagentVisitorKey", "ai.hiagent.visitor-key", true);
        putIfPresent(kv, body, "hiagentCookie", "ai.hiagent.cookie", true);
        putIfPresent(kv, body, "hiagentCsrf", "ai.hiagent.csrf", true);
        config.setMany(kv);
        factory.refresh();

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("saved", true);
        out.put("provider", config.getString("ai.provider", "none"));
        out.put("aiReachable", true);
        if (test != null) {
            out.put("testElapsedMs", test.get("elapsedMs"));
            out.put("testReply", test.get("reply"));
        }
        out.put("message", needTest ? "测试通过，配置已保存并生效" : "配置已保存并生效（本地规则引擎无需测试）");
        return Result.success(out);
    }

    /**
     * 独立测试连接。
     * 请求体可以带表单里的 provider/apiUrl/apiKey 等字段：此时测试的是“尚未保存的配置”；
     * 不带配置字段时测试当前已保存配置。敏感项留空会自动沿用已保存值。
     */
    @PostMapping("/ai/test")
    public Result<Map<String, Object>> test(@RequestBody(required = false) Map<String, String> body) {
        String msg = body != null && body.get("message") != null && !body.get("message").trim().isEmpty()
                ? body.get("message") : "你好";
        return Result.success(runTest(overrides(body), msg, !sameProvider(body)));
    }

    /** 用临时配置真实发一条消息；不写库、不刷新运行时客户端 */
    private Map<String, Object> runTest(Map<String, String> overrides, String msg, boolean providerChanged) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        String provider = providerOf(overrides);
        out.put("provider", provider);
        out.put("configSource", providerChanged ? "form" : "saved+form");
        // 切换 Provider 后禁止复用上一个 Provider 已保存的 Key（否则会“测试成功但测的是旧服务”）
        if (providerChanged && isKeyBasedProvider(provider) && isBlank(overrides.get("ai.api-key"))) {
            out.put("success", false);
            out.put("mode", provider);
            out.put("message", "已切换 AI Provider，请重新填写新服务的 API Key（系统不会复用上一个服务的密钥）");
            return out;
        }

        AIClientFactory.TransientClient built = null;
        long t0 = System.currentTimeMillis();
        try {
            built = factory.buildTransient(overrides);
            AiChatClient c = built == null ? null : built.getClient();
            if (built != null) out.put("target", built.getTarget());
            if (c == null) {
                out.put("success", false);
                out.put("mode", "RULE");
                out.put("message", "未启用大模型（provider=none），AI 助手将使用本地规则模式");
                return out;
            }
            out.put("mode", c.name());
            if (!c.enabled()) {
                out.put("success", false);
                out.put("message", "配置不完整：请检查 API URL / API Key（HiAgent OpenAPI 还需地址、App ID、API Key）"
                        + detail(c));
                return out;
            }
            List<Map<String, String>> history = new ArrayList<Map<String, String>>();
            Map<String, String> um = new HashMap<String, String>();
            um.put("role", "user"); um.put("content", msg); history.add(um);
            String reply = c.chat("你是运维助手，简洁回答。", history);
            boolean ok = reply != null && !reply.trim().isEmpty();
            out.put("success", ok);
            out.put("elapsedMs", System.currentTimeMillis() - t0);
            if (ok) {
                out.put("reply", reply.length() > 500 ? reply.substring(0, 500) : reply);
            } else {
                out.put("message", "测试失败：模型无响应或返回为空" + detail(c));
            }
        } catch (Exception e) {
            out.put("success", false);
            out.put("elapsedMs", System.currentTimeMillis() - t0);
            out.put("message", "测试失败: " + e.getMessage());
        } finally {
            if (built != null) built.close();
        }
        return out;
    }

    private String detail(AiChatClient c) {
        String e = c == null ? null : c.lastError();
        return e == null || e.trim().isEmpty() ? "" : "（" + e + "）";
    }

    /** 表单配置 → 临时覆盖项；未填写的敏感项仅在 Provider 未变时回落已保存值 */
    private Map<String, String> overrides(Map<String, String> body) {
        Map<String, String> o = new LinkedHashMap<String, String>();
        if (body == null) body = new HashMap<String, String>();
        copy(o, body, "provider", "ai.provider");
        copy(o, body, "apiUrl", "ai.apiUrl");
        copy(o, body, "model", "ai.model");
        copy(o, body, "hiagentBaseUrl", "ai.hiagent.base-url");
        copy(o, body, "hiagentAppId", "ai.hiagent.app-id");
        copy(o, body, "hiagentMode", "ai.hiagent.mode");
        copy(o, body, "hiagentOpenapiUrl", "ai.hiagent.openapi-url");
        copy(o, body, "hiagentUserId", "ai.hiagent.user-id");
        boolean same = sameProvider(body);
        copySecret(o, body, "apiKey", "ai.api-key", same);
        copySecret(o, body, "hiagentOpenapiKey", "ai.hiagent.openapi-key", same);
        copySecret(o, body, "hiagentVisitorKey", "ai.hiagent.visitor-key", same);
        copySecret(o, body, "hiagentCookie", "ai.hiagent.cookie", same);
        copySecret(o, body, "hiagentCsrf", "ai.hiagent.csrf", same);
        return o;
    }

    private void copy(Map<String, String> o, Map<String, String> body, String field, String key) {
        if (body.containsKey(field)) o.put(key, body.get(field) == null ? "" : body.get(field));
    }

    private void copySecret(Map<String, String> o, Map<String, String> body, String field, String key,
                            boolean allowSavedFallback) {
        String v = body.get(field);
        if ((v == null || v.trim().isEmpty()) && allowSavedFallback) v = config.getString(key, "");
        if (v != null && !v.trim().isEmpty()) o.put(key, v);
    }

    /** 表单 Provider 是否与已保存 Provider 一致（claude 归一为 anthropic） */
    private boolean sameProvider(Map<String, String> body) {
        String saved = canon(config.getString("ai.provider", "none"));
        String target = body == null || body.get("provider") == null
                ? saved : canon(body.get("provider"));
        return saved.equals(target);
    }

    private String canon(String p) {
        if (p == null) return "none";
        String v = p.trim().toLowerCase();
        return "claude".equals(v) ? "anthropic" : v;
    }

    private String providerOf(Map<String, String> overrides) {
        String p = overrides == null ? null : overrides.get("ai.provider");
        if (p != null && !p.trim().isEmpty()) return p.trim();
        return config.getString("ai.provider", "none");
    }

    private boolean isKeyBasedProvider(String provider) {
        if (provider == null) return false;
        String p = canon(provider);
        return "anthropic".equals(p) || "openai".equals(p) || "deepseek".equals(p) || "qwen".equals(p);
    }

    private boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }

    /** 状态回显 */
    @GetMapping("/ai/status")
    public Result<Map<String, Object>> status() {
        return Result.success(factory.info());
    }

    /** 仅当字段存在时更新；secretOnlyIfNonBlank=true 时留空表示“保持原值” */
    private void putIfPresent(Map<String, String> to, Map<String, String> from, String field, String key,
                              boolean secretOnlyIfNonBlank) {
        if (from == null || !from.containsKey(field)) return;
        String v = from.get(field);
        if (secretOnlyIfNonBlank && (v == null || v.trim().isEmpty())) return;
        to.put(key, v);
    }
}