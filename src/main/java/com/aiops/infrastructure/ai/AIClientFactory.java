package com.aiops.infrastructure.ai;

import com.aiops.service.ConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 运行时大模型工厂：每次按 ConfigService 当前配置选择/构建客户端（配置指纹变化即重建），
 * 支持 refresh() 立即热切换；HiAgent 降级后定时探测恢复。
 */
@Component
public class AIClientFactory {

    private static final Logger log = LoggerFactory.getLogger(AIClientFactory.class);

    private final ConfigService config;

    private volatile AiChatClient cachedClient;
    private volatile String cachedFingerprint;
    private volatile HiAgentSessionManager cachedSession;
    private volatile HiAgentClient cachedHiAgent;   // 供 probeRecover 使用
    private volatile String lastRefreshTime;

    public AIClientFactory(ConfigService config) {
        this.config = config;
    }

    private String cfg(String key, String def) { return config.getString(key, def); }

    /** 当前生效的客户端；无则 null（上层走本地规则）。 */
    public AiChatClient getActive() {
        String fp = fingerprint();
        AiChatClient c = cachedClient;
        if (c != null && fp.equals(cachedFingerprint)) return c;
        synchronized (this) {
            if (cachedClient != null && fp.equals(cachedFingerprint)) return cachedClient;
            rebuild(fp);
            return cachedClient;
        }
    }

    /** 配置变更后强制重建（下次 getActive 依新配置构建）。 */
    public synchronized void refresh() {
        log.info("AI 配置变更，刷新 Provider 客户端…");
        closeSession();
        cachedClient = null; cachedFingerprint = null;
        lastRefreshTime = LocalDateTime.now().toString();
    }

    private void rebuild(String fp) {
        closeSession();
        String provider = cfg("ai.provider", "none");
        TransientClient built = build(provider, null);
        cachedClient = built.client;
        cachedSession = built.session;
        cachedHiAgent = built.session != null ? (HiAgentClient) built.client : null;
        cachedFingerprint = fp;
        lastRefreshTime = LocalDateTime.now().toString();
        log.info("AI 客户端已切换: provider={} enabled={}", provider, cachedClient != null && cachedClient.enabled());
    }

    /**
     * 用“未保存的临时配置”构建客户端，用于保存前测试。
     * overrides 为空时等同于当前已保存配置；测试完成后必须调用 {@link TransientClient#close()}。
     */
    public TransientClient buildTransient(Map<String, String> overrides) {
        String provider = val(overrides, "ai.provider", cfg("ai.provider", "none"));
        return build(provider, overrides);
    }

    private TransientClient build(String provider, Map<String, String> o) {
        if ("hiagent".equalsIgnoreCase(provider)) {
            String mode = val(o, "ai.hiagent.mode", "openapi");
            if ("bypass".equalsIgnoreCase(mode) || "cookie".equalsIgnoreCase(mode)) {
                return buildHiAgentBypass(o);
            }
            String apiUrl = HiAgentOpenApiClient.normalizeUrl(val(o, "ai.hiagent.openapi-url", ""));
            String appId = val(o, "ai.hiagent.app-id", "");
            String apiKey = val(o, "ai.hiagent.openapi-key", "");
            String userId = val(o, "ai.hiagent.user-id", "aiops");
            HiAgentOpenApiClient client = new HiAgentOpenApiClient(apiUrl, appId, apiKey, userId,
                    parseInt(val(o, "ai.hiagent.timeout", "60000")));
            String target = "hiagent-openapi " + client.getEndpoint()
                    + " (app_id=" + client.getAppId() + ", user_id=" + client.getUserId() + ")";
            return new TransientClient(client, null, target);
        }
        if ("anthropic".equalsIgnoreCase(provider) || "claude".equalsIgnoreCase(provider)) {
            String apiUrl = val(o, "ai.apiUrl", "");
            String model = val(o, "ai.model", "deepseek-v4.1-flash-1017");
            AiChatClient client = new AnthropicChatClient(
                    apiUrl,
                    val(o, "ai.api-key", ""),
                    model,
                    val(o, "ai.anthropic.version", "2023-06-01"),
                    parseInt(val(o, "ai.anthropic.max-tokens", "4096"), 4096));
            return new TransientClient(client, null, "anthropic-messages " + endpointFor(apiUrl) + " (model=" + model + ")");
        }
        if ("openai".equalsIgnoreCase(provider) || "deepseek".equalsIgnoreCase(provider)
                || "qwen".equalsIgnoreCase(provider)) {
            String apiUrl = val(o, "ai.apiUrl", "https://api.deepseek.com/v1/chat/completions");
            String model = val(o, "ai.model", "deepseek-chat");
            AiChatClient client = new AIClient(apiUrl, val(o, "ai.api-key", ""), model);
            return new TransientClient(client, null, provider.toLowerCase() + " " + apiUrl + " (model=" + model + ")");
        }
        return new TransientClient(null, null, "provider=" + provider);
    }

    /** 旧版抓包协议（需要浏览器登录 Cookie / CSRF） */
    private TransientClient buildHiAgentBypass(Map<String, String> o) {
        String baseUrl = val(o, "ai.hiagent.base-url", "");
        String appId = val(o, "ai.hiagent.app-id", "");
        HiAgentSessionManager session = new HiAgentSessionManager(
                baseUrl,
                val(o, "ai.hiagent.chat-page", "/product/llm/llm-chat"),
                val(o, "ai.hiagent.visitor-key", ""),
                val(o, "ai.hiagent.cookie", ""),
                val(o, "ai.hiagent.csrf", ""));
        AiChatClient client = new HiAgentClient(session,
                baseUrl,
                appId,
                val(o, "ai.hiagent.chat-path", "/api/bypass/app/"),
                val(o, "ai.hiagent.version", "2023-08-01"),
                val(o, "ai.hiagent.action", "ChatQueryHomeBot"),
                parseInt(val(o, "ai.hiagent.timeout", "60000")));
        String target = "hiagent-bypass " + ((HiAgentClient) client).getEndpoint()
                + " (bot_id=" + appId + ")";
        return new TransientClient(client, session, target);
    }

    private String endpointFor(String baseUrl) {
        if (baseUrl == null) return "";
        String u = baseUrl.trim().replaceAll("/+$", "");
        if (u.endsWith("/v1/messages") || u.endsWith("/messages")) return u;
        if (u.endsWith("/v1")) return u + "/messages";
        return u + "/v1/messages";
    }

    /** overrides 中存在的非空 key 优先，否则回落到已保存配置；空字符串视为“未填写” */
    private String val(Map<String, String> o, String key, String def) {
        if (o != null && o.containsKey(key)) {
            String v = o.get(key);
            if (v != null && !v.trim().isEmpty()) return v;
        }
        String saved = cfg(key, null);
        if (saved != null && !saved.trim().isEmpty()) return saved;
        return def;
    }

    /** 临时客户端；HiAgent 测试结束后需要关闭会话 */
    public static final class TransientClient {
        private final AiChatClient client;
        private final HiAgentSessionManager session;
        private final String target;

        TransientClient(AiChatClient client, HiAgentSessionManager session) {
            this(client, session, client == null ? "" : client.name());
        }

        TransientClient(AiChatClient client, HiAgentSessionManager session, String target) {
            this.client = client;
            this.session = session;
            this.target = target == null ? "" : target;
        }

        public AiChatClient getClient() { return client; }

        /** 本次测试真实使用的地址 / Provider / 模型，供设置页回显 */
        public String getTarget() { return target; }

        public void close() {
            if (session != null) {
                try { session.close(); } catch (Exception ignore) { }
            }
            if (client instanceof HiAgentOpenApiClient) {
                try { ((HiAgentOpenApiClient) client).close(); } catch (Exception ignore) { }
            }
        }
    }

    private void closeSession() {
        if (cachedSession != null) { try { cachedSession.close(); } catch (Exception ignore) { } cachedSession = null; }
    }

    private String fingerprint() {
        return cfg("ai.provider", "none") + "§" + cfg("ai.api-key", "") + "§" + cfg("ai.apiUrl", "") + "§"
                + cfg("ai.model", "") + "§" + cfg("ai.anthropic.version", "") + "§" + cfg("ai.anthropic.max-tokens", "") + "§"
                + cfg("ai.hiagent.mode", "openapi") + "§" + cfg("ai.hiagent.openapi-url", "") + "§"
                + cfg("ai.hiagent.openapi-key", "") + "§" + cfg("ai.hiagent.user-id", "") + "§"
                + cfg("ai.hiagent.base-url", "") + "§" + cfg("ai.hiagent.app-id", "") + "§"
                + cfg("ai.hiagent.cookie", "") + "§" + cfg("ai.hiagent.csrf", "") + "§" + cfg("ai.hiagent.visitor-key", "");
    }

    /** 健康/状态信息（供 health、设置页、Copilot 状态条） */
    public Map<String, Object> info() {
        String provider = cfg("ai.provider", "none");
        Map<String, Object> ai = new LinkedHashMap<String, Object>();
        ai.put("provider", provider);
        boolean hiagent = "hiagent".equalsIgnoreCase(provider);
        String hiMode = cfg("ai.hiagent.mode", "openapi");
        ai.put("mode", hiagent ? hiMode : "");
        if (hiagent) {
            boolean bypass = "bypass".equalsIgnoreCase(hiMode) || "cookie".equalsIgnoreCase(hiMode);
            String endpoint = bypass
                    ? cfg("ai.hiagent.base-url", "") + cfg("ai.hiagent.chat-path", "/api/bypass/app/")
                    : HiAgentOpenApiClient.normalizeUrl(cfg("ai.hiagent.openapi-url", "")) + "/chat_query_v2";
            ai.put("baseUrl", bypass ? cfg("ai.hiagent.base-url", "") : cfg("ai.hiagent.openapi-url", ""));
            ai.put("endpoint", endpoint);
            ai.put("appId", cfg("ai.hiagent.app-id", ""));
            ai.put("userId", cfg("ai.hiagent.user-id", "aiops"));
        } else {
            ai.put("baseUrl", cfg("ai.apiUrl", ""));
            ai.put("endpoint", cfg("ai.apiUrl", ""));
        }
        ai.put("model", cfg("ai.model", ""));
        boolean reachable = false, csrfValid = false, fallback = false;
        AiChatClient c = getActive();
        if (c != null) {
            if (c instanceof HiAgentClient) {
                csrfValid = cachedSession != null && cachedSession.isCsrfValid();
                fallback = ((HiAgentClient) c).isFallbackActive();
                reachable = !fallback && csrfValid;
                ai.put("lastError", c.lastError());
            } else {
                reachable = c.enabled();
                ai.put("lastError", c.lastError());
            }
            ai.put("client", c.name());
        }
        ai.put("reachable", reachable);
        ai.put("csrfValid", csrfValid);
        ai.put("fallbackActive", fallback);
        ai.put("lastRefreshTime", lastRefreshTime);
        ai.put("lastCheckTime", LocalDateTime.now().toString());
        return ai;
    }

    private AiChatClient getActiveQuietly() {
        String fp = fingerprint();
        return cachedClient != null && fp.equals(cachedFingerprint) ? cachedClient : null;
    }

    /** 主动健康检测：触发一次真实握手/调用 */
    public boolean healthCheck() {
        AiChatClient c = getActive();
        if (c instanceof HiAgentClient) return ((HiAgentClient) c).healthCheck();
        if (c instanceof HiAgentOpenApiClient) return ((HiAgentOpenApiClient) c).healthCheck();
        return c != null && c.enabled();
    }

    /** 每 5 分钟探测 HiAgent 恢复 */
    @Scheduled(fixedDelay = 300_000)
    public void scheduledProbe() {
        HiAgentClient h = cachedHiAgent;
        if (h != null) {
            try { h.probeRecover(); } catch (Exception ignore) { }
        }
    }

    private int parseInt(String s) { return parseInt(s, 60000); }

    private int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }
}