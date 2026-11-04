package com.aiops.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * HiAgent 开放接口（OpenAPI）客户端 —— 内网实测协议：
 *
 * 1) POST {base}/create_conversation
 *    Apikey / X-APP-ID / X-APP-User
 *    body: {app_id, user_id, conversation_name}
 *    → Conversation.AppConversationID
 *
 * 2) POST {base}/chat_query_v2
 *    body: {AppConversationID, app_id, user_id, query, stream:true, messages:[...]}
 *    → SSE：data:{"event":"message","answer":"..."} 逐段聚合；其余 message_start /
 *      knowledge_retrieve / message_output_end / message_cost / message_end 事件忽略。
 *
 * 其中 base 形如 http://host:port/api/proxy/api/v1（设置页只需填到这里）。
 * 非 Spring Bean，由 AIClientFactory 按配置动态创建。
 */
public class HiAgentOpenApiClient implements AiChatClient {

    private static final Logger log = LoggerFactory.getLogger(HiAgentOpenApiClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String CREATE_CONVERSATION = "/create_conversation";
    private static final String CHAT_QUERY = "/chat_query_v2";

    private final String baseUrl;
    private final String appId;
    private final String apiKey;
    private final String userId;
    private final int timeout;
    private final String clientSessionId;
    private final CloseableHttpClient http;

    private volatile String appConversationId;
    private volatile String lastError;

    public HiAgentOpenApiClient(String baseUrl, String appId, String apiKey, String userId, int timeout) {
        this.baseUrl = normalizeUrl(baseUrl);
        this.appId = trim(appId);
        this.apiKey = trim(apiKey);
        this.userId = trim(userId).isEmpty() ? "aiops" : trim(userId);
        this.timeout = timeout <= 0 ? 60000 : timeout;
        this.clientSessionId = UUID.randomUUID().toString();
        RequestConfig rc = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(10000))
                .setResponseTimeout(Timeout.ofMilliseconds(this.timeout))
                .build();
        this.http = HttpClients.custom().setDefaultRequestConfig(rc).build();
    }

    @Override
    public boolean enabled() {
        return !baseUrl.isEmpty() && !appId.isEmpty() && !apiKey.isEmpty();
    }

    @Override
    public String name() { return "hiagent-openapi"; }

    /** 实际对话地址（设置页测试结果回显） */
    public String getEndpoint() { return baseUrl + CHAT_QUERY; }

    public String getConversationEndpoint() { return baseUrl + CREATE_CONVERSATION; }

    public String getAppId() { return appId; }

    public String getUserId() { return userId; }

    @Override
    public String lastError() { return lastError; }

    @Override
    public String chat(String systemPrompt, List<Map<String, String>> history) {
        lastError = null;
        if (!enabled()) {
            lastError = "OpenAPI 配置不完整（地址 / App ID / API Key）";
            return null;
        }
        String query = lastUserMessage(history);
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                if (!ensureConversation()) return null;
                String raw = post(chatUrl(), buildChatBody(query, systemPrompt, history));
                String text = parseSseAnswer(raw);
                if ((text == null || text.trim().isEmpty()) && raw != null && !raw.contains("data:")) {
                    String err = extractError(raw);
                    if (err != null) {
                        lastError = err;
                    } else {
                        // 兼容非流式 JSON 返回
                        text = extractText(raw);
                    }
                }
                if (text != null && !text.trim().isEmpty()) {
                    log.info("HiAgent OpenAPI 调用完成 appId={} conversation={} chars={}",
                            appId, appConversationId, text.length());
                    return text;
                }
                String err = extractError(raw);
                lastError = err == null ? "接口返回为空或无法解析: " + abbreviate(raw, 300) : err;
                // 会话失效 → 重建后重试一次
                if (attempt == 0 && looksLikeConversationError(lastError)) {
                    log.warn("HiAgent 会话不可用，重建会话后重试: {}", lastError);
                    appConversationId = null;
                    continue;
                }
                log.warn("HiAgent OpenAPI 响应无法解析: {}", abbreviate(raw, 500));
                return null;
            } catch (Exception e) {
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
                log.warn("HiAgent OpenAPI 调用异常: {}", lastError);
                return null;
            }
        }
        return null;
    }

    /** 确保存在可用的 AppConversationID；失败时 lastError 记录服务端原始错误 */
    private boolean ensureConversation() {
        if (appConversationId != null && !appConversationId.isEmpty()) return true;
        try {
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("app_id", appId);
            body.put("user_id", userId);
            body.put("conversation_name", "AI-Ops");
            String raw = post(conversationUrl(), MAPPER.writeValueAsString(body));
            String err = extractError(raw);
            if (err != null) {
                lastError = err;
                log.warn("HiAgent 创建会话失败: {}", err);
                return false;
            }
            JsonNode conv = MAPPER.readTree(raw).path("Conversation");
            String id = conv.path("AppConversationID").asText("");
            if (id.isEmpty()) {
                lastError = "创建会话响应缺少 Conversation.AppConversationID: " + abbreviate(raw, 300);
                log.warn(lastError);
                return false;
            }
            appConversationId = id;
            log.info("HiAgent 会话已创建: {}", id);
            return true;
        } catch (Exception e) {
            lastError = "创建会话失败: " + e.getClass().getSimpleName() + ": " + e.getMessage();
            log.warn(lastError);
            return false;
        }
    }

    private String post(String url, String body) throws Exception {
        HttpPost post = new HttpPost(url);
        post.setHeader("Content-Type", "application/json; charset=utf-8");
        post.setHeader("Accept", "application/json, text/event-stream");
        post.setHeader("Apikey", apiKey);
        post.setHeader("X-APP-ID", appId);
        post.setHeader("X-APP-User", userId);
        post.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON.withCharset(StandardCharsets.UTF_8)));
        try (CloseableHttpResponse resp = http.execute(post)) {
            int code = resp.getCode();
            String raw = resp.getEntity() == null ? "" : EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);
            if (code >= 400) {
                String err = extractError(raw);
                lastError = "HTTP " + code + (err == null ? " " + abbreviate(raw, 300) : " " + err);
                log.warn("HiAgent OpenAPI HTTP {}: {}", code, lastError);
                return raw;
            }
            return raw;
        }
    }

    private String buildChatBody(String query, String systemPrompt, List<Map<String, String>> history) throws Exception {
        List<Map<String, String>> messages = new ArrayList<Map<String, String>>();
        if (systemPrompt != null && !systemPrompt.trim().isEmpty()) {
            Map<String, String> sys = new LinkedHashMap<String, String>();
            sys.put("role", "system");
            sys.put("content", systemPrompt);
            messages.add(sys);
        }
        if (history != null) {
            for (Map<String, String> m : history) {
                if (m == null) continue;
                String role = trim(m.get("role"));
                String content = m.get("content");
                if (content == null || content.trim().isEmpty()) continue;
                if (!"user".equals(role) && !"assistant".equals(role) && !"system".equals(role)) continue;
                Map<String, String> one = new LinkedHashMap<String, String>();
                one.put("role", role);
                one.put("content", content);
                messages.add(one);
            }
        }
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("AppConversationID", appConversationId);
        body.put("app_id", appId);
        body.put("user_id", userId);
        body.put("query", query);
        body.put("stream", true);
        body.put("messages", messages);
        return MAPPER.writeValueAsString(body);
    }

    private String lastUserMessage(List<Map<String, String>> history) {
        if (history != null) {
            for (int i = history.size() - 1; i >= 0; i--) {
                Map<String, String> m = history.get(i);
                if (m == null) continue;
                if ("user".equals(trim(m.get("role"))) && m.get("content") != null
                        && !m.get("content").trim().isEmpty()) {
                    return m.get("content");
                }
            }
        }
        return "你好";
    }

    /** 聚合 HiAgent SSE：只取 event=message 的 answer 字段 */
    String parseSseAnswer(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        if (!raw.contains("data:")) return null;
        StringBuilder out = new StringBuilder();
        for (String line : raw.split("\\r?\\n")) {
            String l = line.trim();
            if (!l.startsWith("data:")) continue;
            String payload = l.substring(5).trim();
            // 兼容 v1 的 data:data: {...} 双层前缀
            while (payload.startsWith("data:")) payload = payload.substring(5).trim();
            if (payload.isEmpty() || "[DONE]".equals(payload)) continue;
            try {
                JsonNode n = MAPPER.readTree(payload);
                String event = n.path("event").asText("");
                if ("error".equals(event) || "task_failed".equals(event)) {
                    String msg = firstText(n.path("message"), n.path("error").path("message"), n.path("answer"));
                    if (msg != null) lastError = msg;
                    continue;
                }
                if ("message".equals(event)) {
                    String piece = firstText(n.path("answer"), n.path("content"), n.path("delta").path("content"));
                    if (piece != null) {
                        // 兼容增量与累计两种输出
                        if (out.length() > 0 && piece.startsWith(out.toString())) {
                            out.setLength(0);
                            out.append(piece);
                        } else {
                            out.append(piece);
                        }
                    }
                }
            } catch (Exception ignore) { /* 非 JSON 片段 */ }
        }
        return out.length() == 0 ? null : out.toString();
    }

    /** 从响应中提取错误信息（HiAgent 统一为 ResponseMetadata.Error.{Code,Message}） */
    String extractError(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            JsonNode root = MAPPER.readTree(raw);
            JsonNode err = root.path("ResponseMetadata").path("Error");
            if (err.isObject() && !err.path("Message").asText("").isEmpty()) {
                String code = err.path("Code").asText("");
                String msg = err.path("Message").asText("");
                return code.isEmpty() ? msg : code + ": " + msg;
            }
            JsonNode base = root.path("BaseResp");
            if (base.isObject() && base.path("StatusCode").asInt(0) != 0
                    && !base.path("StatusMessage").asText("").isEmpty()) {
                return base.path("StatusMessage").asText();
            }
            JsonNode e = root.path("error");
            if (e.isObject()) {
                String msg = firstText(e.path("message"), e.path("msg"));
                if (msg != null) return msg;
            } else if (e.isTextual() && !e.asText().isEmpty()) {
                return e.asText();
            }
            if (root.path("code").asInt(200) >= 400 && !root.path("message").asText("").isEmpty()) {
                return root.path("message").asText();
            }
        } catch (Exception ignore) { }
        return null;
    }

    /** 兼容多种返回结构（非流式 JSON / 部分网关直接返回答案） */
    String extractText(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        if (t.isEmpty()) return null;
        try {
            JsonNode root = MAPPER.readTree(t);
            String[] paths = {
                    "Result.content", "Result.answer", "Result.message", "Result.text", "Result.output",
                    "Data.content", "data.content", "data.answer", "data.message", "data.text",
                    "choices.0.message.content", "choices.0.text", "content", "answer", "output", "text"
            };
            for (String p : paths) {
                String s = firstText(at(root, p));
                if (s != null && !s.trim().isEmpty()) return s;
            }
            return deepFind(root, 0);
        } catch (Exception e) {
            // 非 JSON：直接当文本返回
            return t;
        }
    }

    private JsonNode at(JsonNode root, String path) {
        JsonNode cur = root;
        for (String seg : path.split("\\.")) {
            if (cur == null) return null;
            if (seg.matches("\\d+")) cur = cur.path(Integer.parseInt(seg));
            else cur = cur.path(seg);
        }
        return cur;
    }

    private String deepFind(JsonNode node, int depth) {
        if (node == null || depth > 5) return null;
        if (node.isObject()) {
            String[] names = {"content", "answer", "text", "message", "output"};
            for (String name : names) {
                JsonNode v = node.get(name);
                if (v != null && v.isTextual() && !v.asText().trim().isEmpty()) return v.asText();
            }
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                if ("ResponseMetadata".equalsIgnoreCase(e.getKey())) continue;
                String found = deepFind(e.getValue(), depth + 1);
                if (found != null) return found;
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                String found = deepFind(child, depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    private String firstText(JsonNode... nodes) {
        for (JsonNode n : nodes) {
            if (n != null && n.isTextual() && !n.asText().isEmpty()) return n.asText();
        }
        return null;
    }

    private boolean looksLikeConversationError(String msg) {
        if (msg == null) return false;
        String m = msg.toLowerCase();
        return m.contains("appconversationid") || m.contains("conversation") && (m.contains("not") || m.contains("invalid") || m.contains("expire"));
    }

    private String chatUrl() { return baseUrl + CHAT_QUERY; }

    private String conversationUrl() { return baseUrl + CREATE_CONVERSATION; }

    /**
     * 允许填基础地址（.../api/proxy/api/v1）、旧版 /chat_query 或 /chat_query_v2；
     * 统一归一为 /api/proxy/api/v1 形式的基础地址。
     */
    static String normalizeUrl(String url) {
        String u = trim(url);
        if (u.isEmpty()) return "";
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (u.endsWith("/chat_query_v2")) u = u.substring(0, u.length() - "/chat_query_v2".length());
        else if (u.endsWith("/chat_query")) u = u.substring(0, u.length() - "/chat_query".length());
        else if (u.endsWith("/create_conversation")) u = u.substring(0, u.length() - "/create_conversation".length());
        return u;
    }

    private static String trim(String s) { return s == null ? "" : s.trim(); }

    private static String abbreviate(String s, int max) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    public void close() { try { http.close(); } catch (Exception ignore) { } }

    /** 健康检测：真实走一次建会话 + 对话 */
    public boolean healthCheck() {
        List<Map<String, String>> h = new ArrayList<Map<String, String>>();
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("role", "user"); m.put("content", "你好");
        h.add(m);
        String reply = chat(null, h);
        return reply != null && !reply.trim().isEmpty();
    }
}