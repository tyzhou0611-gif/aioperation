package com.aiops.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容大模型客户端（DeepSeek/通义/OpenAI），非 Spring Bean，由 AIClientFactory 动态构建。
 * 未配置 api-key 时 enabled=false，上层降级本地规则。
 */
public class AIClient implements AiChatClient {

    private static final Logger log = LoggerFactory.getLogger(AIClient.class);

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String apiKey;
    private final String apiUrl;
    private final String model;

    public AIClient(String apiUrl, String apiKey, String model) {
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public boolean enabled() {
        return apiKey != null && !apiKey.trim().isEmpty()
                && apiUrl != null && !apiUrl.trim().isEmpty();
    }

    @Override
    public String name() { return "openai-compatible"; }

    @Override
    public String chat(String systemPrompt, List<Map<String, String>> history) {
        if (!enabled()) return null;
        try {
            List<Map<String, String>> messages = new ArrayList<Map<String, String>>();
            Map<String, String> sys = new HashMap<String, String>();
            sys.put("role", "system"); sys.put("content", systemPrompt);
            messages.add(sys);
            if (history != null) messages.addAll(history);

            Map<String, Object> body = new HashMap<String, Object>();
            body.put("model", model);
            body.put("messages", messages);
            body.put("temperature", 0.3);

            HttpHeaders h = new HttpHeaders();
            h.setContentType(MediaType.APPLICATION_JSON);
            h.set("Authorization", "Bearer " + apiKey);
            HttpEntity<Map<String, Object>> req = new HttpEntity<Map<String, Object>>(body, h);

            ResponseEntity<String> resp = restTemplate.postForEntity(apiUrl, req, String.class);
            JsonNode root = mapper.readTree(resp.getBody());
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            return content.isMissingNode() ? null : content.asText();
        } catch (Exception e) {
            log.warn("大模型调用失败（将降级规则引擎）: {}", e.getMessage());
            return null;
        }
    }
}