package com.aiops.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Anthropic Messages API 客户端（Claude 协议 / 兼容网关）。
 * 支持 x-api-key 与 Authorization: Bearer 两种鉴权头，兼容内网网关。
 * 非 Spring Bean，由 AIClientFactory 动态构建。
 */
public class AnthropicChatClient implements AiChatClient {

    private static final Logger log = LoggerFactory.getLogger(AnthropicChatClient.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final String anthropicVersion;
    private final int maxTokens;
    private volatile String lastError;

    public AnthropicChatClient(String baseUrl, String apiKey, String model,
                               String anthropicVersion, int maxTokens) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null || model.trim().isEmpty() ? "deepseek-v4.1-flash-1017" : model.trim();
        this.anthropicVersion = anthropicVersion == null || anthropicVersion.trim().isEmpty()
                ? "2023-06-01" : anthropicVersion.trim();
        this.maxTokens = maxTokens <= 0 ? 4096 : maxTokens;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(10000);
        f.setReadTimeout(180000);
        this.restTemplate = new RestTemplate(f);
    }

    @Override
    public boolean enabled() {
        return !baseUrl.isEmpty() && !apiKey.isEmpty() && !model.isEmpty();
    }

    @Override
    public String name() { return "anthropic-messages"; }

    @Override
    public String lastError() { return lastError; }

    @Override
    public String chat(String systemPrompt, List<Map<String, String>> history) {
        return chatWithThinking(systemPrompt, history).getText();
    }

    @Override
    public ChatReply chatWithThinking(String systemPrompt, List<Map<String, String>> history) {
        lastError = null;
        if (!enabled()) {
            lastError = "API URL / API Key / Model 未填写完整";
            return new ChatReply(null, null);
        }
        try {
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("model", model);
            body.put("max_tokens", maxTokens);
            body.put("temperature", 0.3);
            body.put("stream", false);
            if (systemPrompt != null && !systemPrompt.trim().isEmpty()) {
                body.put("system", systemPrompt);
            }
            body.put("messages", buildMessages(history));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-api-key", apiKey);
            headers.set("Authorization", "Bearer " + apiKey);
            headers.set("anthropic-version", anthropicVersion);

            HttpEntity<Map<String, Object>> req = new HttpEntity<Map<String, Object>>(body, headers);
            ResponseEntity<String> resp = restTemplate.postForEntity(endpoint(), req, String.class);
            JsonNode root = mapper.readTree(resp.getBody());

            if (root.has("error")) {
                lastError = "网关返回错误: " + root.path("error").toString();
                log.warn("Anthropic 网关返回错误: {}", root.path("error").toString());
                return new ChatReply(null, null);
            }
            StringBuilder text = new StringBuilder();
            StringBuilder thinking = new StringBuilder();
            for (JsonNode block : root.path("content")) {
                String type = block.path("type").asText("");
                if ("text".equals(type)) {
                    text.append(block.path("text").asText(""));
                } else if ("thinking".equals(type)) {
                    String th = block.path("thinking").asText("");
                    if (!th.trim().isEmpty()) {
                        if (thinking.length() > 0) thinking.append("\n");
                        thinking.append(th.trim());
                    }
                }
            }
            String thinkingText = thinking.length() == 0 ? null : thinking.toString();
            if (text.length() == 0) {
                lastError = "模型返回无 text 内容（stop_reason=" + root.path("stop_reason").asText("") + "）";
                log.warn("Anthropic 返回无 text 内容, stop_reason={}, content={}",
                        root.path("stop_reason").asText(""), root.path("content").toString());
                return new ChatReply(null, thinkingText);
            }
            log.info("Anthropic 调用完成 model={} stop={} in={} out={} thinking={}",
                    root.path("model").asText(model), root.path("stop_reason").asText(""),
                    root.path("usage").path("input_tokens").asInt(0),
                    root.path("usage").path("output_tokens").asInt(0),
                    thinkingText == null ? 0 : thinkingText.length());
            return new ChatReply(text.toString(), thinkingText);
        } catch (Exception e) {
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.warn("Anthropic 调用失败（将降级）: {}", e.getMessage());
            return new ChatReply(null, null);
        }
    }

    private String endpoint() {
        if (baseUrl.endsWith("/v1/messages") || baseUrl.endsWith("/messages")) return baseUrl;
        if (baseUrl.endsWith("/v1")) return baseUrl + "/messages";
        return baseUrl + "/v1/messages";
    }

    /**
     * Anthropic 要求消息角色为 user/assistant，且尽量交替。
     * 处理：丢弃 system、合并连续同角色、去掉开头 assistant、保证至少一条消息。
     */
    private List<Map<String, String>> buildMessages(List<Map<String, String>> history) {
        List<Map<String, String>> out = new ArrayList<Map<String, String>>();
        if (history != null) {
            for (Map<String, String> h : history) {
                if (h == null) continue;
                String role = h.get("role");
                String content = h.get("content");
                if (content == null || content.trim().isEmpty()) continue;
                if (!"user".equals(role) && !"assistant".equals(role)) continue;
                if (out.isEmpty() && "assistant".equals(role)) continue;
                if (!out.isEmpty() && role.equals(out.get(out.size() - 1).get("role"))) {
                    Map<String, String> last = out.get(out.size() - 1);
                    last.put("content", last.get("content") + "\n" + content);
                } else {
                    Map<String, String> m = new LinkedHashMap<String, String>();
                    m.put("role", role);
                    m.put("content", content);
                    out.add(m);
                }
            }
        }
        if (out.isEmpty()) {
            Map<String, String> m = new LinkedHashMap<String, String>();
            m.put("role", "user");
            m.put("content", "你好");
            out.add(m);
        }
        return out;
    }
}