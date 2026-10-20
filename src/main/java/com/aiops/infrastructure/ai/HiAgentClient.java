package com.aiops.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 内网 HiAgent 客户端（抓包协议，非 Spring Bean，由 AIClientFactory 动态创建/销毁）。
 * 惰性 + 容错：不可达/CSRF失效 → 返回 null，上层降级本地规则；连续失败进入 fallback。
 */
public class HiAgentClient implements AiChatClient {

    private static final Logger log = LoggerFactory.getLogger(HiAgentClient.class);

    private final HiAgentSessionManager session;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;
    private final String appId;
    private final String chatPath;
    private final String version;
    private final String action;
    private final int timeout;

    private final AtomicBoolean fallbackActive = new AtomicBoolean(false);
    private final AtomicInteger consecutiveFail = new AtomicInteger(0);
    private volatile String lastError;

    public HiAgentClient(HiAgentSessionManager session, String baseUrl, String appId,
                         String chatPath, String version, String action, int timeout) {
        this.session = session;
        this.baseUrl = baseUrl;
        this.appId = appId;
        this.chatPath = chatPath;
        this.version = version;
        this.action = action;
        this.timeout = timeout;
    }

    @Override
    public boolean enabled() { return session.configured(); }

    @Override
    public String name() { return "hiagent"; }

    public boolean isFallbackActive() { return fallbackActive.get(); }
    public String getLastError() { return lastError; }

    /** 组装：把 system + 最近用户消息合并为发给 HomeBot 的单一 message */
    private String composeMessage(List<Map<String, String>> history) {
        StringBuilder sb = new StringBuilder();
        if (history != null) {
            for (Map<String, String> m : history) {
                String c = m.get("content");
                if (c == null) continue;
                if ("system".equals(m.get("role"))) { /* HiAgent 固定 bot，system 前缀轻量带入 */
                    sb.append(c).append("\n\n");
                } else if ("user".equals(m.get("role"))) {
                    sb.append("用户问题：").append(c).append("\n");
                }
            }
        }
        if (sb.length() == 0 && history != null && !history.isEmpty()) {
            sb.append(history.get(history.size() - 1).get("content"));
        }
        return sb.length() == 0 ? "你好" : sb.toString();
    }

    @Override
    public String chat(String systemPrompt, List<Map<String, String>> history) {
        if (!enabled()) return null;
        if (fallbackActive.get()) return null; // 已降级，直接让上层走规则
        // 网络类重试2次
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                if (!session.ensureSession()) { markFail("CSRF会话未就绪"); return null; }
                String text = doChatOnce(systemPrompt, history);
                if (text != null) { markSuccess(); return text; }
                return null;
            } catch (CsrfException ce) {
                log.warn("HiAgent CSRF 失效，重建会话后重试: {}", ce.getMessage());
                session.refresh();
                if (attempt >= 1) { markFail(ce.getMessage()); return null; }
            } catch (Exception e) {
                lastError = e.getMessage();
                log.warn("HiAgent 调用异常(第{}次): {}", attempt + 1, e.getMessage());
                sleepBackoff(attempt);
            }
        }
        return null;
    }

    private String doChatOnce(String systemPrompt, List<Map<String, String>> history) throws Exception {
        String url = baseUrl + chatPath + "?Version=" + urlEnc(version) + "&Action=" + urlEnc(action);
        HttpPost post = new HttpPost(url);
        post.setHeader("Content-Type", "application/json; charset=utf-8");
        post.setHeader("Accept", "application/json, text/event-stream");
        post.setHeader("x-csrf-token", nvl(session.getCsrfToken()));
        post.setHeader("origin", baseUrl);
        post.setHeader("referer", baseUrl + "/product/llm/llm-chat");
        post.setHeader("User-Agent", "Mozilla/5.0");
        String vk = session.getVisitorKey();
        if (vk != null && !vk.isEmpty()) post.setHeader("app-visitor-key", vk);
        String cookie = session.getCookieHeader();
        if (cookie != null && !cookie.isEmpty()) post.setHeader("Cookie", cookie);

        String message = composeMessage(history);
        StringBuilder body = new StringBuilder("{");
        body.append("\"message\":\"").append(jsonEsc(message)).append("\",");
        body.append("\"query\":\"").append(jsonEsc(message)).append("\",");
        body.append("\"content\":\"").append(jsonEsc(message)).append("\",");
        body.append("\"bot_id\":\"").append(jsonEsc(appId)).append("\",");
        body.append("\"stream\":true}");
        post.setEntity(new StringEntity(body.toString(),
                org.apache.hc.core5.http.ContentType.APPLICATION_JSON.withCharset(StandardCharsets.UTF_8)));

        try (ClassicHttpResponse resp = session.execute(post)) {
            int code = resp.getCode();
            if (code == 403 || code == 401) throw new CsrfException("HTTP " + code + " CSRF/鉴权失效");
            if (code >= 400) { lastError = "HTTP " + code; return null; }
            String result = parseSse(resp);
            if (result == null) throw new CsrfException("响应无有效内容(可能CSRF失效)");
            return result;
        }
    }

    /** 解析 SSE 流，聚合文本 */
    private String parseSse(ClassicHttpResponse resp) throws Exception {
        if (resp.getEntity() == null) return null;
        BufferedReader br = new BufferedReader(
                new InputStreamReader(resp.getEntity().getContent(), StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) {
            line = line.trim();
            if (!line.startsWith("data:")) continue;
            String payload = line.substring(5).trim();
            if (payload.isEmpty()) continue;
            if ("[DONE]".equals(payload)) break;
            try {
                JsonNode n = mapper.readTree(payload);
                String piece = extractContent(n);
                if (piece != null) out.append(piece);
            } catch (Exception ignore) { /* 非JSON片段跳过 */ }
        }
        return out.length() == 0 ? null : out.toString();
    }

    private String extractContent(JsonNode n) {
        JsonNode data = n.path("data");
        if (data.isObject()) {
            JsonNode t = data.path("type");
            if (t.isTextual() && "answer".equals(t.asText())) {
                return firstText(data.path("content"), data.path("answer"));
            }
            String c = firstText(data.path("content"), data.path("answer"), data.path("delta").path("content"));
            if (c != null) return c;
        }
        // OpenAI 风格兜底
        JsonNode ch = n.path("choices").path(0).path("message").path("content");
        return ch.isTextual() ? ch.asText() : null;
    }

    private String firstText(JsonNode... nodes) {
        for (JsonNode node : nodes) if (node != null && node.isTextual() && !node.asText().isEmpty()) return node.asText();
        return null;
    }

    /** 健康检测 */
    public boolean healthCheck() {
        if (!enabled()) return false;
        try {
            boolean sess = session.ensureSession();
            String text = doChatOnce(null, java.util.Collections.singletonList(
                    mkMsg("user", "你好")));
            return sess && text != null && !text.isEmpty();
        } catch (Exception e) {
            lastError = e.getMessage();
            return false;
        }
    }

    /** 探测恢复（由 AIClientFactory 定时调用）；成功则解除降级。 */
    public void probeRecover() {
        if (!fallbackActive.get()) return;
        try {
            if (session.ensureSession() && session.isCsrfValid()) {
                fallbackActive.set(false);
                consecutiveFail.set(0);
                log.info("HiAgent 恢复，重新启用大模型对话");
            }
        } catch (Exception ignore) { }
    }

    private void markFail(String why) {
        lastError = why;
        if (consecutiveFail.incrementAndGet() >= 3) {
            if (fallbackActive.compareAndSet(false, true)) {
                log.warn("HiAgent 连续失败≥3次，自动降级为本地规则模式（原因: {}）", why);
            }
        }
    }
    private void markSuccess() { consecutiveFail.set(0); }

    private Map<String, String> mkMsg(String role, String content) {
        java.util.HashMap<String, String> m = new java.util.HashMap<String, String>();
        m.put("role", role); m.put("content", content); return m;
    }

    private void sleepBackoff(int attempt) {
        try { Thread.sleep((long) Math.pow(2, attempt) * 500); } catch (InterruptedException ignore) { }
    }

    private String nvl(String s) { return s == null ? "" : s; }
    private String urlEnc(String s) { try { return java.net.URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; } }
    private String jsonEsc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    static class CsrfException extends Exception {
        CsrfException(String m) { super(m); }
    }
}