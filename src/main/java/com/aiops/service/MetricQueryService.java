package com.aiops.service;

import com.aiops.common.ServiceException;
import com.aiops.entity.MetricDefinition;
import com.aiops.entity.MetricSample;
import com.aiops.repository.MetricSampleRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 统一指标查询服务：
 * - prometheus: 通过 PromQL 查询 Prometheus
 * - push      : 查询外部推送到 metric_sample 的样本
 * - http_json : GET/POST 任意 HTTP JSON 接口，按 jsonPath 取值（当前值）
 *
 * 返回结构统一为：
 * { code,name,unit,source,query,start,end,step,times,series[],seriesCount,latest,latestLabel,error? }
 */
@Service
public class MetricQueryService {

    private static final Logger log = LoggerFactory.getLogger(MetricQueryService.class);
    private static final int MAX_PUSH_ROWS = 50000;

    private final MetricCatalogService catalogService;
    private final MonitorService monitorService;
    private final MetricSampleRepository sampleRepository;
    private final ObjectMapper mapper = new ObjectMapper();
    private final RestTemplate rest;
    @Value("${app.metrics.sample.retention-days:30}")
    private int retentionDays;

    public MetricQueryService(MetricCatalogService catalogService, MonitorService monitorService,
                              MetricSampleRepository sampleRepository) {
        this.catalogService = catalogService;
        this.monitorService = monitorService;
        this.sampleRepository = sampleRepository;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(5000);
        f.setReadTimeout(10000);
        this.rest = new RestTemplate(f);
    }

    /** 每天 03:17 清理过期推送样本，避免 metric_sample 无限增长 */
    @Scheduled(cron = "0 17 3 * * *")
    @Transactional
    public void cleanupOldSamples() {
        int days = retentionDays <= 0 ? 30 : retentionDays;
        long cutoff = System.currentTimeMillis() - days * 86400000L;
        long deleted = sampleRepository.deleteBySampleTimeBefore(cutoff);
        if (deleted > 0) {
            log.info("已清理 {} 条过期推送样本（保留 {} 天）", deleted, days);
        }
    }

    /** 查询已注册指标 */
    public Map<String, Object> query(String code, String range, Long start, Long end,
                                     String instance, Map<String, String> vars) {
        MetricDefinition def = catalogService.getByCode(code);
        if (def.getEnabled() != null && def.getEnabled() == 0) {
            throw new ServiceException(400, "指标已停用: " + code);
        }
        return execute(def, range, start, end, instance, vars);
    }

    /** 预览未保存/正在编辑的指标定义 */
    public Map<String, Object> preview(MetricDefinition def, String range, Long start, Long end,
                                       String instance, Map<String, String> vars) {
        MetricDefinition normalized = catalogService.validateForPreview(def);
        return execute(normalized, range, start, end, instance, vars);
    }

    private Map<String, Object> execute(MetricDefinition def, String range, Long start, Long end,
                                        String instance, Map<String, String> vars) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("code", def.getCode());
        out.put("name", def.getName());
        out.put("unit", def.getUnit());
        out.put("source", def.getSourceType());
        long[] spec = monitorService.resolveRange(range, start, end);
        out.put("start", spec[0]);
        out.put("end", spec[1]);
        out.put("step", spec[2]);
        try {
            if ("prometheus".equalsIgnoreCase(def.getSourceType())) {
                String expr = applyVarsForPromql(def.getQuery(), vars, instance);
                out.put("query", expr);
                Map<String, Object> p = monitorService.querySeries(expr, spec[0], spec[1], spec[2], instance);
                out.putAll(p);
            } else if ("push".equalsIgnoreCase(def.getSourceType())) {
                out.put("query", def.getCode());
                queryPush(def, spec, out);
            } else if ("http_json".equalsIgnoreCase(def.getSourceType())) {
                String url = applyVarsForUrl(def.getQuery(), vars, instance);
                out.put("query", url);
                queryHttp(def, url, out);
            } else {
                throw new ServiceException(400, "不支持的数据源: " + def.getSourceType());
            }
            out.put("seriesCount", seriesOf(out).size());
            monitorService.fillLatest(out);
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            out.putIfAbsent("times", new ArrayList<Long>());
            out.putIfAbsent("series", new ArrayList<Map<String, Object>>());
            out.put("seriesCount", 0);
            out.put("latest", null);
            out.put("latestLabel", null);
            out.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            log.warn("指标查询失败 [{}]: {}", def.getCode(), e.getMessage());
        }
        return out;
    }

    // ==================== Push 样本查询 ====================

    private void queryPush(MetricDefinition def, long[] spec, Map<String, Object> out) throws Exception {
        long startMs = spec[0] * 1000;
        long endMs = spec[1] * 1000;
        long total = sampleRepository.countByMetricCodeAndSampleTimeBetween(def.getCode(), startMs, endMs);
        if (total > MAX_PUSH_ROWS) {
            throw new ServiceException(400, "样本过多（" + total + " 条），请缩小时间范围");
        }
        List<MetricSample> samples = sampleRepository.findByMetricCodeAndSampleTimeBetweenOrderBySampleTimeAsc(
                def.getCode(), startMs, endMs, PageRequest.of(0, MAX_PUSH_ROWS));
        Map<String, Map<Long, Double>> groups = new LinkedHashMap<String, Map<Long, Double>>();
        Map<String, Map<String, String>> labelMap = new LinkedHashMap<String, Map<String, String>>();
        TreeSet<Long> timeSet = new TreeSet<Long>();
        for (MetricSample s : samples) {
            String key = s.getLabelsJson() == null || s.getLabelsJson().trim().isEmpty() ? "{}" : s.getLabelsJson();
            Map<Long, Double> values = groups.get(key);
            if (values == null) {
                values = new TreeMap<Long, Double>();
                groups.put(key, values);
                labelMap.put(key, parseLabels(key));
            }
            values.put(s.getSampleTime(), s.getValue());
            timeSet.add(s.getSampleTime());
        }
        List<Long> times = new ArrayList<Long>(timeSet);
        List<Map<String, Object>> series = new ArrayList<Map<String, Object>>();
        Set<String> usedNames = new LinkedHashSet<String>();
        for (Map.Entry<String, Map<Long, Double>> e : groups.entrySet()) {
            Map<String, Object> s = new LinkedHashMap<String, Object>();
            Map<String, String> labels = labelMap.get(e.getKey());
            String baseName = displayName(labels);
            String name = baseName;
            int dup = 0;
            while (usedNames.contains(name)) { dup++; name = baseName + " (" + dup + ")"; }
            usedNames.add(name);
            List<Object> data = new ArrayList<Object>();
            for (Long ts : times) {
                Double v = e.getValue().get(ts);
                data.add(v == null ? null : Math.round(v * 10.0) / 10.0);
            }
            s.put("name", name);
            s.put("labels", labels);
            s.put("data", data);
            series.add(s);
        }
        out.put("times", times);
        out.put("series", series);
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> parseLabels(String json) {
        try {
            Object o = mapper.readValue(json, Object.class);
            if (o instanceof Map) {
                Map<String, String> out = new LinkedHashMap<String, String>();
                for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                    out.put(String.valueOf(e.getKey()), e.getValue() == null ? "" : String.valueOf(e.getValue()));
                }
                return out;
            }
        } catch (Exception ignore) { }
        return new LinkedHashMap<String, String>();
    }

    private String displayName(Map<String, String> labels) {
        if (labels == null || labels.isEmpty()) return "合计";
        for (String key : new String[]{"hostname", "instance", "app", "service", "pod", "container", "job"}) {
            String v = labels.get(key);
            if (v != null && !v.isEmpty()) return v;
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : labels.entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    // ==================== HTTP JSON 查询 ====================

    private void queryHttp(MetricDefinition def, String url, Map<String, Object> out) throws Exception {
        JsonNode cfg = mapper.readTree(def.getConfigJson() == null || def.getConfigJson().trim().isEmpty()
                ? "{}" : def.getConfigJson());
        String method = cfg.path("method").asText("GET").trim().toUpperCase();
        if (!"GET".equals(method) && !"POST".equals(method)) {
            throw new ServiceException(400, "HTTP JSON 仅支持 GET/POST");
        }
        HttpHeaders headers = new HttpHeaders();
        JsonNode hs = cfg.path("headers");
        if (hs.isObject()) {
            java.util.Iterator<Map.Entry<String, JsonNode>> it = hs.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                headers.set(e.getKey(), e.getValue().asText(""));
            }
        }
        if (headers.getContentType() == null) headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity;
        if ("POST".equals(method)) {
            JsonNode bodyNode = cfg.path("body");
            String body = bodyNode.isMissingNode() || bodyNode.isNull() ? "" :
                    (bodyNode.isTextual() ? bodyNode.asText() : bodyNode.toString());
            entity = new HttpEntity<String>(body, headers);
        } else {
            entity = new HttpEntity<String>(headers);
        }
        ResponseEntity<String> resp = rest.exchange(url, HttpMethod.valueOf(method), entity, String.class);
        String body = resp.getBody();
        JsonNode root = mapper.readTree(body == null ? "" : body);
        String path = cfg.path("jsonPath").asText("");
        JsonNode valueNode = at(root, path);
        Double value = toDouble(valueNode);
        if (value == null) {
            throw new IllegalStateException("JSONPath [" + path + "] 结果不是数值: " + abbreviate(String.valueOf(valueNode), 200));
        }
        long now = System.currentTimeMillis();
        Map<String, Object> s = new LinkedHashMap<String, Object>();
        s.put("name", "当前值");
        s.put("labels", new LinkedHashMap<String, String>());
        s.put("data", Collections.singletonList(Math.round(value * 10.0) / 10.0));
        out.put("times", Collections.singletonList(now));
        out.put("series", Collections.singletonList(s));
        out.put("httpStatus", resp.getStatusCodeValue());
    }

    private JsonNode at(JsonNode root, String path) {
        if (root == null) return null;
        if (path == null || path.trim().isEmpty()) return root;
        String p = path.trim();
        if (p.startsWith("$")) p = p.substring(1);
        if (p.startsWith(".")) p = p.substring(1);
        JsonNode cur = root;
        for (String seg : p.split("\\.")) {
            if (seg == null || seg.isEmpty()) continue;
            if (cur == null) return null;
            if (seg.matches("\\d+")) cur = cur.path(Integer.parseInt(seg));
            else cur = cur.path(seg);
        }
        return cur;
    }

    private Double toDouble(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) return null;
        if (n.isNumber()) return n.asDouble();
        if (n.isBoolean()) return n.asBoolean() ? 1.0 : 0.0;
        if (n.isTextual()) {
            try { return Double.parseDouble(n.asText().trim()); } catch (Exception ignore) { return null; }
        }
        return null;
    }

    // ==================== 变量替换 ====================

    private String applyVarsForPromql(String query, Map<String, String> vars, String instance) {
        String out = query == null ? "" : query;
        if (vars != null) {
            for (Map.Entry<String, String> e : vars.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                out = out.replace("$" + e.getKey(), escapeProm(e.getValue()));
            }
        }
        if (instance == null || instance.trim().isEmpty() || "all".equalsIgnoreCase(instance.trim())) {
            out = out.replace("$instance", ".*");
        } else {
            out = out.replace("$instance", escapeProm(instance.trim()));
        }
        return out;
    }

    private String applyVarsForUrl(String url, Map<String, String> vars, String instance) {
        String out = url == null ? "" : url;
        if (vars != null) {
            for (Map.Entry<String, String> e : vars.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                out = out.replace("$" + e.getKey(), urlEncode(e.getValue()));
            }
        }
        if (instance != null && !instance.trim().isEmpty() && !"all".equalsIgnoreCase(instance.trim())) {
            out = out.replace("$instance", urlEncode(instance.trim()));
        }
        return out;
    }

    private String escapeProm(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String urlEncode(String s) {
        try { return URLEncoder.encode(s == null ? "" : s, "UTF-8"); }
        catch (Exception e) { return s == null ? "" : s; }
    }

    private String abbreviate(String s, int max) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> seriesOf(Map<String, Object> out) {
        Object o = out.get("series");
        if (o instanceof List) return (List<Map<String, Object>>) o;
        return new ArrayList<Map<String, Object>>();
    }
}