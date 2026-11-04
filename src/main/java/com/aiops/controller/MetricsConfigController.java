package com.aiops.controller;

import com.aiops.common.Result;
import com.aiops.service.ConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 外部数据源配置：Prometheus / Grafana / 指标源。
 * 保存后写入 sys_config，指标源与 Prometheus 地址均按请求实时读取，无需重启。
 */
@RestController
@RequestMapping("/api/system/config")
public class MetricsConfigController {

    private final ConfigService config;
    private final ObjectMapper mapper = new ObjectMapper();
    private final RestTemplate restTemplate;

    public MetricsConfigController(ConfigService config) {
        this.config = config;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(5000);
        f.setReadTimeout(10000);
        this.restTemplate = new RestTemplate(f);
    }

    @GetMapping("/metrics")
    public Result<Map<String, Object>> get() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("source", config.getString("metrics.source", "prometheus"));
        out.put("prometheusUrl", config.getString("prometheus.url", ""));
        out.put("grafanaUrl", config.getString("grafana.url", ""));
        out.put("logSource", config.getString("log.source", "default"));
        out.put("serverStatusSource", config.getString("server.status.source", "auto"));
        return Result.success(out);
    }

    @PostMapping("/metrics")
    public Result<Map<String, Object>> save(@RequestBody Map<String, String> body) {
        if (body.containsKey("source")) {
            String source = body.get("source");
            if (!"default".equalsIgnoreCase(source) && !"prometheus".equalsIgnoreCase(source)) {
                return Result.error("指标源只能是 default 或 prometheus");
            }
            config.setString("metrics.source", source);
        }
        if (body.containsKey("prometheusUrl")) config.setString("prometheus.url", normalize(body.get("prometheusUrl")));
        if (body.containsKey("grafanaUrl")) config.setString("grafana.url", normalize(body.get("grafanaUrl")));
        if (body.containsKey("logSource")) config.setString("log.source", body.get("logSource"));
        if (body.containsKey("serverStatusSource")) {
            String src = body.get("serverStatusSource");
            if (!"auto".equalsIgnoreCase(src) && !"prometheus".equalsIgnoreCase(src)
                    && !"ssh".equalsIgnoreCase(src) && !"icmp".equalsIgnoreCase(src)) {
                return Result.error("服务器状态来源只能是 auto / prometheus / ssh / icmp");
            }
            config.setString("server.status.source", src.toLowerCase());
        }

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("saved", true);
        out.put("source", config.getString("metrics.source", "prometheus"));
        out.put("prometheusUrl", config.getString("prometheus.url", ""));
        out.put("serverStatusSource", config.getString("server.status.source", "auto"));
        out.put("message", "数据源配置已保存并即时生效");
        return Result.success(out);
    }

    /** 真实连接测试：Prometheus 查询 up；Grafana 请求 /api/health */
    @PostMapping("/metrics/test")
    public Result<Map<String, Object>> test(@RequestBody(required = false) Map<String, String> body) {
        String type = body != null && body.get("type") != null ? body.get("type") : "prometheus";
        String url = body != null && body.get("url") != null && !body.get("url").trim().isEmpty()
                ? body.get("url") : config.getString("prometheus.url", "");
        url = normalize(url);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("type", type);
        out.put("url", url);
        if (url.isEmpty()) {
            out.put("reachable", false);
            out.put("message", "未填写地址");
            return Result.success(out);
        }
        long start = System.currentTimeMillis();
        try {
            if ("grafana".equalsIgnoreCase(type)) {
                ResponseEntity<String> resp = restTemplate.getForEntity(URI.create(url + "/api/health"), String.class);
                out.put("reachable", true);
                out.put("httpStatus", resp.getStatusCodeValue());
                out.put("message", "Grafana 可达（HTTP " + resp.getStatusCodeValue() + "）");
            } else {
                URI uri = UriComponentsBuilder.fromHttpUrl(url + "/api/v1/query")
                        .queryParam("query", "up").build().encode().toUri();
                ResponseEntity<String> resp = restTemplate.getForEntity(uri, String.class);
                JsonNode root = mapper.readTree(resp.getBody());
                boolean ok = "success".equals(root.path("status").asText());
                int series = root.path("data").path("result").size();
                out.put("reachable", ok);
                out.put("httpStatus", resp.getStatusCodeValue());
                out.put("series", series);
                out.put("message", ok ? "Prometheus 可达，up 指标返回 " + series + " 条实例" : "响应不是预期格式");
            }
        } catch (Exception e) {
            out.put("reachable", false);
            out.put("message", "无法连接: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
        out.put("elapsedMs", System.currentTimeMillis() - start);
        return Result.success(out);
    }

    private String normalize(String url) {
        if (url == null) return "";
        String v = url.trim();
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        return v;
    }
}