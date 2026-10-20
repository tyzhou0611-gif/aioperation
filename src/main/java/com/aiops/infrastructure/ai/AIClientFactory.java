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
        AiChatClient client = null;
        if ("hiagent".equalsIgnoreCase(provider)) {
            HiAgentSessionManager session = new HiAgentSessionManager(
                    cfg("ai.hiagent.base-url", ""),
                    cfg("ai.hiagent.chat-page", "/product/llm/llm-chat"),
                    cfg("ai.hiagent.visitor-key", ""),
                    cfg("ai.hiagent.cookie", ""),
                    cfg("ai.hiagent.csrf", ""));
            client = new HiAgentClient(session,
                    cfg("ai.hiagent.base-url", ""),
                    cfg("ai.hiagent.app-id", ""),
                    cfg("ai.hiagent.chat-path", "/api/bypass/app/"),
                    cfg("ai.hiagent.version", "2023-08-01"),
                    cfg("ai.hiagent.action", "ChatQueryHomeBot"),
                    parseInt(cfg("ai.hiagent.timeout", "60000")));
            cachedSession = session; cachedHiAgent = (HiAgentClient) client;
        } else if ("openai".equalsIgnoreCase(provider) || "deepseek".equalsIgnoreCase(provider)
                || "qwen".equalsIgnoreCase(provider)) {
            String url = cfg("ai.apiUrl", "https://api.deepseek.com/v1/chat/completions");
            client = new AIClient(url, cfg("ai.api-key", ""), cfg("ai.model", "deepseek-chat"));
        }
        cachedClient = client; cachedFingerprint = fp;
        lastRefreshTime = LocalDateTime.now().toString();
        log.info("AI 客户端已切换: provider={} enabled={}", provider, client != null && client.enabled());
    }

    private void closeSession() {
        if (cachedSession != null) { try { cachedSession.close(); } catch (Exception ignore) { } cachedSession = null; }
    }

    private String fingerprint() {
        return cfg("ai.provider", "none") + "§" + cfg("ai.api-key", "") + "§" + cfg("ai.apiUrl", "") + "§"
                + cfg("ai.model", "") + "§" + cfg("ai.hiagent.base-url", "") + "§" + cfg("ai.hiagent.app-id", "") + "§"
                + cfg("ai.hiagent.cookie", "") + "§" + cfg("ai.hiagent.csrf", "") + "§" + cfg("ai.hiagent.visitor-key", "");
    }

    /** 健康/状态信息（供 health、设置页、Copilot 状态条） */
    public Map<String, Object> info() {
        String provider = cfg("ai.provider", "none");
        Map<String, Object> ai = new LinkedHashMap<String, Object>();
        ai.put("provider", provider);
        ai.put("baseUrl", "hiagent".equalsIgnoreCase(provider) ? cfg("ai.hiagent.base-url", "") : "");
        boolean reachable = false, csrfValid = false, fallback = false;
        AiChatClient c = cachedClient;
        if (c != null && c == getActiveQuietly()) {
            if (c instanceof HiAgentClient) {
                csrfValid = cachedSession != null && cachedSession.isCsrfValid();
                fallback = ((HiAgentClient) c).isFallbackActive();
                reachable = !fallback && csrfValid;
                ai.put("lastError", ((HiAgentClient) c).getLastError());
            } else {
                reachable = c.enabled();
            }
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

    private int parseInt(String s) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 60000; } }
}