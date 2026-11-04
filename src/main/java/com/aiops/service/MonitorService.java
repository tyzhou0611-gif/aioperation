package com.aiops.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.URLEncoder;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 原生监控面板数据服务：直接查询 Prometheus，替代 Grafana iframe（无需 Grafana 登录）。
 */
@Service
public class MonitorService {

    private static final Logger log = LoggerFactory.getLogger(MonitorService.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConfigService config;
    private final MonitorPanelConfigService panelConfigService;
    private final String defaultUrl;

    public MonitorService(@Value("${app.prometheus.url:}") String defaultUrl, ConfigService config,
                          MonitorPanelConfigService panelConfigService) {
        this.defaultUrl = normalize(defaultUrl);
        this.config = config;
        this.panelConfigService = panelConfigService;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(5000);
        f.setReadTimeout(20000);
        this.restTemplate = new RestTemplate(f);
        log.info("原生监控面板数据源: {}", this.defaultUrl.isEmpty() ? "(未配置，可在系统设置中填写)" : this.defaultUrl);
    }

    /** 当前生效地址：DB（页面配置）优先，其次 yml 默认值 */
    private String prometheusUrl() {
        return normalize(config.getString("prometheus.url", defaultUrl));
    }

    private String normalize(String url) {
        return url == null ? "" : url.trim().replaceAll("/+$", "");
    }

    /** 仪表盘聚合数据 */
    public Map<String, Object> overview(String range, String instance) {
        return overview(range, instance, null, null);
    }

    /** 仪表盘聚合数据；start/end 为自定义时间（epoch 秒），同时提供时优先于 range */
    public Map<String, Object> overview(String range, String instance, Long start, Long end) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        boolean custom = start != null && end != null;
        result.put("range", custom ? "custom" : range);
        result.put("instance", isAll(instance) ? "all" : instance);
        result.put("updatedAt", System.currentTimeMillis());
        String url = prometheusUrl();
        result.put("prometheusUrl", url);
        Map<String, Object> panelCfg = panelConfigService.get();
        result.put("panelConfig", panelCfg);
        boolean netOn = chartEnabled(panelCfg, "network");
        boolean loadOn = chartEnabled(panelCfg, "load");

        if (url.isEmpty()) {
            result.put("error", "未配置 Prometheus 地址（系统设置 → 外部数据源）");
            return result;
        }

        try {
            Map<String, String> names = hostNames();
            List<Map<String, Object>> hosts = hosts(names);
            JsonNode up = rawInstant("up{job=~\"node|node_exporter\"}");
            if (!up.isArray() || up.size() == 0) up = rawInstant("up{job!~\"prometheus|pushgateway\"}");

            Map<String, Double> upMap = instantMap(up);
            Map<String, Double> uptimeMap = instantMap(rawInstant("time() - node_boot_time_seconds"));
            Map<String, Double> loadMap = instantMap(rawInstant("node_load1"));

            long[] spec = rangeSpec(range, start, end);
            result.put("start", spec[0]);
            result.put("end", spec[1]);
            result.put("step", spec[2]);
            String inst = instanceSelector(instance);

            JsonNode cpuRaw = rawRange(
                    "100 - (avg by(instance) (rate(node_cpu_seconds_total" + sel("mode=\"idle\"", inst) + "[15m])) * 100)",
                    spec[0], spec[1], spec[2]);
            JsonNode memRaw = rawRange(
                    "(1 - node_memory_MemAvailable_bytes" + sel(inst)
                            + " / node_memory_MemTotal_bytes" + sel(inst) + ") * 100",
                    spec[0], spec[1], spec[2]);
            JsonNode diskRaw = rawRange(
                    "max by(instance) ((1 - node_filesystem_avail_bytes"
                            + sel("mountpoint=\"/\"", "fstype!~\"tmpfs|overlay\"", inst)
                            + " / node_filesystem_size_bytes"
                            + sel("mountpoint=\"/\"", "fstype!~\"tmpfs|overlay\"", inst) + ") * 100)",
                    spec[0], spec[1], spec[2]);
            String netAgg = isAll(instance) ? "sum" : "sum by(instance)";
            Map<String, Object> cpuPanel = matrix(cpuRaw, names, "");
            Map<String, Object> memPanel = matrix(memRaw, names, "");
            Map<String, Object> diskPanel = matrix(diskRaw, names, "");
            Map<String, Object> netPanel = null;
            if (netOn) {
                JsonNode rxRaw = rawRange(
                        netAgg + " (rate(node_network_receive_bytes_total"
                                + sel("device!~\"lo|veth.*|docker.*|br-.*\"", inst) + "[15m])) * 8",
                        spec[0], spec[1], spec[2]);
                JsonNode txRaw = rawRange(
                        netAgg + " (rate(node_network_transmit_bytes_total"
                                + sel("device!~\"lo|veth.*|docker.*|br-.*\"", inst) + "[15m])) * 8",
                        spec[0], spec[1], spec[2]);
                Map<String, Object> rxPanel = matrix(rxRaw, names, " ↓");
                Map<String, Object> txPanel = matrix(txRaw, names, " ↑");
                netPanel = new LinkedHashMap<String, Object>();
                netPanel.put("times", rxPanel.get("times"));
                List<Map<String, Object>> netSeries = new ArrayList<Map<String, Object>>();
                List<Map<String, Object>> rxSeries = asSeries(rxPanel.get("series"));
                List<Map<String, Object>> txSeries = asSeries(txPanel.get("series"));
                if (isAll(instance)) {
                    if (!rxSeries.isEmpty()) rxSeries.get(0).put("name", "总下载");
                    if (!txSeries.isEmpty()) txSeries.get(0).put("name", "总上传");
                }
                netSeries.addAll(rxSeries);
                netSeries.addAll(txSeries);
                netPanel.put("series", netSeries);
            }

            Map<String, Object> loadPanel = loadOn ? loadPanel(instance, names, spec) : null;

            Map<String, Object> panels = new LinkedHashMap<String, Object>();
            panels.put("cpu", cpuPanel);
            panels.put("memory", memPanel);
            panels.put("disk", diskPanel);
            if (netPanel != null) panels.put("network", netPanel);
            if (loadPanel != null) panels.put("load", loadPanel);
            result.put("panels", panels);
            result.put("customPanels", customPanels(panelCfg, instance, names, spec));

            // 当前值（用于主机表和概览卡片）
            Map<String, Double> cpuLatest = latestByInstance(cpuRaw);
            Map<String, Double> memLatest = latestByInstance(memRaw);
            Map<String, Double> diskLatest = latestByInstance(diskRaw);

            for (Map<String, Object> h : hosts) {
                String in = String.valueOf(h.get("instance"));
                h.put("up", upMap.containsKey(in) ? upMap.get(in) > 0 : null);
                h.put("cpu", round(cpuLatest.get(in)));
                h.put("memory", round(memLatest.get(in)));
                h.put("disk", round(diskLatest.get(in)));
                h.put("load1", round(loadMap.get(in)));
                h.put("uptime", uptimeMap.get(in) == null ? null : uptimeMap.get(in).longValue());
            }
            result.put("hosts", hosts);

            result.put("summary", summary(hosts, upMap, cpuLatest, memLatest, diskLatest, instance));
            return result;
        } catch (Exception e) {
            log.warn("原生监控数据查询失败: {}", e.getMessage());
            result.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return result;
        }
    }

    // ==================== 自定义重点指标 ====================

    @SuppressWarnings("unchecked")
    private boolean chartEnabled(Map<String, Object> cfg, String key) {
        if (cfg == null) return true;
        Object charts = cfg.get("charts");
        if (!(charts instanceof Map)) return true;
        Object c = ((Map<String, Object>) charts).get(key);
        if (!(c instanceof Map)) return true;
        Object enabled = ((Map<String, Object>) c).get("enabled");
        return !(enabled instanceof Boolean) || (Boolean) enabled;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> customPanels(Map<String, Object> cfg, String instance,
                                                   Map<String, String> names, long[] spec) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        if (cfg == null) return out;
        Object raw = cfg.get("customMetrics");
        if (!(raw instanceof List)) return out;
        for (Object item : (List<Object>) raw) {
            if (!(item instanceof Map)) continue;
            Map<String, Object> def = (Map<String, Object>) item;
            if (Boolean.FALSE.equals(def.get("enabled"))) continue;
            String query = def.get("query") == null ? "" : String.valueOf(def.get("query")).trim();
            if (query.isEmpty()) continue;
            String expr = applyInstancePlaceholder(query, instance);
            Map<String, Object> p = new LinkedHashMap<String, Object>();
            p.put("id", def.get("id"));
            p.put("name", def.get("name"));
            p.put("unit", def.get("unit"));
            p.put("type", def.get("type"));
            p.put("span", def.get("span"));
            p.put("color", def.get("color"));
            p.put("highlight", Boolean.TRUE.equals(def.get("highlight")));
            p.put("description", def.get("description"));
            p.put("query", expr);
            try {
                JsonNode rawRange = rawRange(expr, spec[0], spec[1], spec[2]);
                Map<String, Object> panel = matrix(rawRange, names, "", instance);
                p.put("times", panel.get("times"));
                p.put("series", panel.get("series"));
                fillLatest(p);
            } catch (Exception e) {
                p.put("times", new ArrayList<Long>());
                p.put("series", new ArrayList<Map<String, Object>>());
                p.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                log.warn("自定义指标查询失败 [{}]: {}", def.get("name"), e.getMessage());
            }
            out.add(p);
        }
        return out;
    }

    /** 指标试运行（设置面板“开发功能”里的测试按钮） */
    public Map<String, Object> testMetric(String query, String unit, String instance,
                                          String range, Long start, Long end) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) {
            out.put("error", "查询语句不能为空");
            return out;
        }
        if (q.length() > 500) {
            out.put("error", "查询语句不能超过 500 个字符");
            return out;
        }
        try {
            long[] spec = rangeSpec(range, start, end);
            String expr = applyInstancePlaceholder(q, instance);
            Map<String, String> names = hostNames();
            JsonNode raw = rawRange(expr, spec[0], spec[1], spec[2]);
            Map<String, Object> panel = matrix(raw, names, "", instance);
            out.put("query", expr);
            out.put("unit", unit == null || unit.trim().isEmpty() ? "num" : unit.trim());
            out.put("times", panel.get("times"));
            out.put("series", panel.get("series"));
            out.put("seriesCount", asSeries(panel.get("series")).size());
            fillLatest(out);
        } catch (Exception e) {
            out.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        return out;
    }

    /** 支持在 PromQL 中用 $instance 占位，例如 node_load1{instance="$instance"} */
    private String applyInstancePlaceholder(String query, String instance) {
        if (query == null) return "";
        if (isAll(instance)) return query.replace("$instance", ".*");
        return query.replace("$instance", escape(instance.trim()));
    }

    // ==================== 指标查询 ====================

    private List<Map<String, Object>> hosts(Map<String, String> names) throws Exception {
        JsonNode up = rawInstant("up{job=~\"node|node_exporter\"}");
        if (!up.isArray() || up.size() == 0) up = rawInstant("up{job!~\"prometheus|pushgateway\"}");
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        for (JsonNode r : up) {
            String in = r.path("metric").path("instance").asText("");
            if (in.isEmpty()) continue;
            Map<String, Object> h = new LinkedHashMap<String, Object>();
            h.put("instance", in);
            h.put("ip", stripPort(in));
            h.put("hostname", names.containsKey(in) ? names.get(in) : stripPort(in));
            h.put("job", r.path("metric").path("job").asText(""));
            list.add(h);
        }
        list.sort(Comparator.comparing(a -> String.valueOf(a.get("ip"))));
        return list;
    }

    private Map<String, String> hostNames() throws Exception {
        Map<String, String> map = new LinkedHashMap<String, String>();
        try {
            JsonNode result = rawInstant("node_uname_info");
            for (JsonNode r : result) {
                String in = r.path("metric").path("instance").asText("");
                String nodename = r.path("metric").path("nodename").asText("");
                if (!in.isEmpty() && !nodename.isEmpty() && !map.containsKey(in)) map.put(in, nodename);
            }
        } catch (Exception e) {
            log.debug("读取 node_uname_info 失败: {}", e.getMessage());
        }
        return map;
    }

    private Map<String, Object> loadPanel(String instance, Map<String, String> names, long[] spec) throws Exception {
        Map<String, Object> panel = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> series = new ArrayList<Map<String, Object>>();
        List<Long> times = new ArrayList<Long>();
        if (!isAll(instance)) {
            String inst = instanceSelector(instance);
            String[] exprs = {"node_load1" + sel(inst), "node_load5" + sel(inst), "node_load15" + sel(inst)};
            String[] labels = {"load1", "load5", "load15"};
            for (int i = 0; i < exprs.length; i++) {
                Map<String, Object> p = matrix(rawRange(exprs[i], spec[0], spec[1], spec[2]), names, "");
                if (times.isEmpty()) times = asLongList(p.get("times"));
                List<Map<String, Object>> ss = asSeries(p.get("series"));
                if (!ss.isEmpty()) {
                    Map<String, Object> s = ss.get(0);
                    s.put("name", labels[i]);
                    series.add(s);
                }
            }
        } else {
            String[] exprs = {"avg(node_load1)", "avg(node_load5)", "avg(node_load15)"};
            String[] labels = {"平均 load1", "平均 load5", "平均 load15"};
            for (int i = 0; i < exprs.length; i++) {
                Map<String, Object> p = matrix(rawRange(exprs[i], spec[0], spec[1], spec[2]), names, "");
                if (times.isEmpty()) times = asLongList(p.get("times"));
                List<Map<String, Object>> ss = asSeries(p.get("series"));
                if (!ss.isEmpty()) {
                    Map<String, Object> s = ss.get(0);
                    s.put("name", labels[i]);
                    series.add(s);
                }
            }
        }
        panel.put("times", times);
        panel.put("series", series);
        return panel;
    }

    /** Prometheus matrix → {times:[epochMillis], series:[{name,data:[number|null]}]} */
    private Map<String, Object> matrix(JsonNode result, Map<String, String> names, String suffix) {
        return matrix(result, names, suffix, null);
    }

    /** 同上；onlyInstance 非空时只保留该主机的序列（自定义指标选中单主机时使用） */
    private Map<String, Object> matrix(JsonNode result, Map<String, String> names, String suffix, String onlyInstance) {
        Map<String, Object> panel = new LinkedHashMap<String, Object>();
        List<Long> times = new ArrayList<Long>();
        List<Map<String, Object>> series = new ArrayList<Map<String, Object>>();
        Map<String, String> state = new LinkedHashMap<String, String>();
        try {
            if (result == null || !result.isArray() || result.size() == 0) {
                panel.put("times", times);
                panel.put("series", series);
                return panel;
            }
            // 用第一条序列的时间轴
            JsonNode first = result.get(0).path("values");
            List<Long> baseTimes = new ArrayList<Long>();
            for (JsonNode p : first) baseTimes.add((long) (p.path(0).asDouble() * 1000));
            times = baseTimes;
            boolean filterInstance = onlyInstance != null && !onlyInstance.trim().isEmpty() && !isAll(onlyInstance);

            for (JsonNode r : result) {
                String inst = r.path("metric").path("instance").asText("");
                if (filterInstance && !inst.isEmpty() && !onlyInstance.trim().equals(inst)) continue;
                String base = inst.isEmpty() ? "" : (names.containsKey(inst) ? names.get(inst) : stripPort(inst));
                String name = base.isEmpty() ? "合计" : base + suffix;
                if (name.isEmpty()) name = "series";
                Map<Long, Double> byTs = new TreeMap<Long, Double>();
                for (JsonNode p : r.path("values")) {
                    long ts = (long) (p.path(0).asDouble() * 1000);
                    Double v = parseValue(p.path(1).asText());
                    if (v != null) byTs.put(ts, v);
                }
                List<Object> data = new ArrayList<Object>();
                for (Long ts : baseTimes) {
                    Double v = byTs.get(ts);
                    data.add(v == null ? null : round(v));
                }
                // 同名序列追加序号，避免 ECharts 图例合并
                String finalName = name;
                int dup = 0;
                while (state.containsKey(finalName)) { dup++; finalName = name + " (" + dup + ")"; }
                state.put(finalName, finalName);
                Map<String, Object> s = new LinkedHashMap<String, Object>();
                s.put("name", finalName);
                s.put("data", data);
                series.add(s);
            }
            panel.put("times", times);
            panel.put("series", series);
        } catch (Exception e) {
            panel.put("times", times);
            panel.put("series", series);
            panel.put("error", e.getMessage());
        }
        return panel;
    }

    /** 统一时间范围解析（供指标注册中心复用） */
    public long[] resolveRange(String range, Long start, Long end) {
        return rangeSpec(range, start, end);
    }

    /**
     * 通用 PromQL 查询（指标注册中心使用）：
     * 返回 {times, series:[{name,labels,data}], seriesCount}；失败时返回 error。
     */
    public Map<String, Object> querySeries(String expr, long start, long end, long step, String onlyInstance) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        try {
            JsonNode raw = rawRange(expr, start, end, step);
            return detailedSeries(raw, onlyInstance);
        } catch (Exception e) {
            out.put("times", new ArrayList<Long>());
            out.put("series", new ArrayList<Map<String, Object>>());
            out.put("seriesCount", 0);
            out.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return out;
        }
    }

    /** 带完整标签的矩阵解析（通用指标查询用） */
    private Map<String, Object> detailedSeries(JsonNode result, String onlyInstance) {
        Map<String, Object> panel = new LinkedHashMap<String, Object>();
        List<Long> times = new ArrayList<Long>();
        List<Map<String, Object>> series = new ArrayList<Map<String, Object>>();
        try {
            if (result == null || !result.isArray() || result.size() == 0) {
                panel.put("times", times);
                panel.put("series", series);
                panel.put("seriesCount", 0);
                return panel;
            }
            JsonNode first = result.get(0).path("values");
            for (JsonNode p : first) times.add((long) (p.path(0).asDouble() * 1000));
            boolean filterInstance = onlyInstance != null && !onlyInstance.trim().isEmpty() && !isAll(onlyInstance);
            Map<String, String> hostNames = hostNames();
            Set<String> usedNames = new java.util.LinkedHashSet<String>();

            for (JsonNode r : result) {
                Map<String, String> labels = new LinkedHashMap<String, String>();
                java.util.Iterator<Map.Entry<String, JsonNode>> it = r.path("metric").fields();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> e = it.next();
                    if ("__name__".equals(e.getKey())) continue;
                    labels.put(e.getKey(), e.getValue().asText(""));
                }
                String inst = labels.get("instance");
                if (filterInstance && inst != null && !inst.isEmpty() && !onlyInstance.trim().equals(inst)) continue;

                String baseName = displayName(labels, hostNames);
                String name = baseName;
                int dup = 0;
                while (usedNames.contains(name)) { dup++; name = baseName + " (" + dup + ")"; }
                usedNames.add(name);

                Map<Long, Double> byTs = new TreeMap<Long, Double>();
                for (JsonNode p : r.path("values")) {
                    long ts = (long) (p.path(0).asDouble() * 1000);
                    Double v = parseValue(p.path(1).asText());
                    if (v != null) byTs.put(ts, v);
                }
                List<Object> data = new ArrayList<Object>();
                for (Long ts : times) {
                    Double v = byTs.get(ts);
                    data.add(v == null ? null : round(v));
                }
                Map<String, Object> s = new LinkedHashMap<String, Object>();
                s.put("name", name);
                s.put("labels", labels);
                s.put("data", data);
                series.add(s);
            }
            panel.put("times", times);
            panel.put("series", series);
            panel.put("seriesCount", series.size());
        } catch (Exception e) {
            panel.put("times", times);
            panel.put("series", series);
            panel.put("seriesCount", series.size());
            panel.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        return panel;
    }

    private String displayName(Map<String, String> labels, Map<String, String> hostNames) {
        if (labels.containsKey("hostname") && !labels.get("hostname").isEmpty()) return labels.get("hostname");
        String inst = labels.get("instance");
        if (inst != null && !inst.isEmpty()) {
            return hostNames.containsKey(inst) ? hostNames.get(inst) : stripPort(inst);
        }
        for (String key : new String[]{"app", "service", "pod", "container", "job"}) {
            String v = labels.get(key);
            if (v != null && !v.isEmpty()) return v;
        }
        if (labels.isEmpty()) return "合计";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : labels.entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /** 计算最新值：单序列取原值；多序列按单位求和/平均（供指标注册中心复用） */
    public void fillLatest(Map<String, Object> p) {
        List<Map<String, Object>> series = asSeries(p.get("series"));
        List<Double> vals = new ArrayList<Double>();
        String singleName = null;
        for (Map<String, Object> s : series) {
            Object data = s.get("data");
            if (!(data instanceof List)) continue;
            List<?> list = (List<?>) data;
            for (int i = list.size() - 1; i >= 0; i--) {
                Object v = list.get(i);
                if (v instanceof Number) {
                    vals.add(((Number) v).doubleValue());
                    if (series.size() == 1) singleName = s.get("name") == null ? null : String.valueOf(s.get("name"));
                    break;
                }
            }
        }
        if (vals.isEmpty()) {
            p.put("latest", null);
            p.put("latestLabel", null);
            return;
        }
        if (vals.size() == 1) {
            p.put("latest", round(vals.get(0)));
            p.put("latestLabel", singleName);
            return;
        }
        String unit = p.get("unit") == null ? "num" : String.valueOf(p.get("unit"));
        boolean sum = "bps".equals(unit) || "bytes".equals(unit) || "count".equals(unit);
        double total = 0;
        for (Double v : vals) total += v;
        p.put("latest", round(sum ? total : total / vals.size()));
        p.put("latestLabel", sum ? "合计" : "平均");
    }

    private Map<String, Double> latestByInstance(JsonNode result) {
        Map<String, Double> map = new LinkedHashMap<String, Double>();
        if (result == null || !result.isArray()) return map;
        for (JsonNode r : result) {
            String in = r.path("metric").path("instance").asText("");
            if (in.isEmpty()) continue;
            JsonNode values = r.path("values");
            if (values.isArray() && values.size() > 0) {
                Double v = parseValue(values.get(values.size() - 1).path(1).asText());
                if (v != null) map.put(in, v);
            }
        }
        return map;
    }

    private Map<String, Double> instantMap(JsonNode result) {
        Map<String, Double> map = new LinkedHashMap<String, Double>();
        if (result == null || !result.isArray()) return map;
        for (JsonNode r : result) {
            String in = r.path("metric").path("instance").asText("");
            if (in.isEmpty()) continue;
            JsonNode v = r.path("value");
            if (v.isArray() && v.size() >= 2) {
                Double d = parseValue(v.get(1).asText());
                if (d != null) map.put(in, d);
            }
        }
        return map;
    }

    private Map<String, Object> summary(List<Map<String, Object>> hosts, Map<String, Double> up,
                                        Map<String, Double> cpu, Map<String, Double> mem, Map<String, Double> disk,
                                        String instance) {
        Map<String, Object> s = new LinkedHashMap<String, Object>();
        boolean selected = !isAll(instance);
        List<Map<String, Object>> scope = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> h : hosts) {
            if (selected && !instance.equals(h.get("instance"))) continue;
            scope.add(h);
        }
        int total = selected ? 1 : hosts.size();
        int online = 0;
        for (Map<String, Object> h : scope) {
            Double v = up.get(String.valueOf(h.get("instance")));
            if (v != null && v > 0) online++;
        }
        s.put("totalHosts", total);
        s.put("onlineHosts", online);
        s.put("offlineHosts", Math.max(0, total - online));
        s.put("avgCpu", averageSelected(cpu, scope, selected));
        s.put("avgMemory", averageSelected(mem, scope, selected));
        double maxDisk = 0;
        for (Map<String, Object> h : scope) {
            Double v = disk.get(String.valueOf(h.get("instance")));
            if (v != null && v > maxDisk) maxDisk = v;
        }
        s.put("maxDisk", round(maxDisk));
        return s;
    }

    private Double averageSelected(Map<String, Double> values, List<Map<String, Object>> scope, boolean selected) {
        if (selected) {
            for (Map<String, Object> h : scope) {
                Double v = values.get(String.valueOf(h.get("instance")));
                return round(v);
            }
            return null;
        }
        double sum = 0; int n = 0;
        for (Map<String, Object> h : scope) {
            Double v = values.get(String.valueOf(h.get("instance")));
            if (v != null) { sum += v; n++; }
        }
        return n == 0 ? null : round(sum / n);
    }

    // ==================== Prometheus HTTP ====================

    private JsonNode rawInstant(String promql) throws Exception {
        String base = prometheusUrl();
        if (base.isEmpty()) throw new IllegalStateException("未配置 Prometheus 地址，请在系统设置 → 外部数据源中填写");
        URI uri = promUri(base, "/api/v1/query", "query", promql);
        return request(uri).path("data").path("result");
    }

    private JsonNode rawRange(String promql, long start, long end, long step) throws Exception {
        String base = prometheusUrl();
        if (base.isEmpty()) throw new IllegalStateException("未配置 Prometheus 地址，请在系统设置 → 外部数据源中填写");
        URI uri = promUri(base, "/api/v1/query_range",
                "query", promql,
                "start", String.valueOf(start),
                "end", String.valueOf(end),
                "step", String.valueOf(step));
        return request(uri).path("data").path("result");
    }

    /**
     * 手动拼接 URL：UriComponentsBuilder.encode() 不会编码 query 值中的 '+'，
     * Prometheus 会把 '+' 当成空格，导致正则/表达式出错。
     */
    private URI promUri(String base, String path, String... kv) {
        StringBuilder sb = new StringBuilder(base).append(path).append('?');
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (i > 0) sb.append('&');
            sb.append(kv[i]).append('=').append(urlEncode(kv[i + 1]));
        }
        return URI.create(sb.toString());
    }

    private String urlEncode(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return s == null ? "" : s;
        }
    }

    private JsonNode request(URI uri) throws Exception {
        ResponseEntity<String> resp = restTemplate.getForEntity(uri, String.class);
        JsonNode root = objectMapper.readTree(resp.getBody());
        if (!"success".equals(root.path("status").asText())) {
            throw new RuntimeException("Prometheus 查询失败: " + root.path("error").asText(resp.getBody()));
        }
        return root;
    }

    // ==================== 工具 ====================

    private long[] rangeSpec(String range, Long start, Long end) {
        long now = Instant.now().getEpochSecond();
        if (start != null && end != null) {
            long s = Math.min(start, end);
            long e = Math.min(Math.max(start, end), now);
            if (s >= e) s = e - 60;
            return new long[]{s, e, niceStep(e - s)};
        }
        String r = range == null ? "1h" : range;
        if ("6h".equals(r)) return new long[]{now - 6 * 3600, now, 300};
        if ("24h".equals(r)) return new long[]{now - 24 * 3600, now, 600};
        if ("7d".equals(r)) return new long[]{now - 7 * 24 * 3600, now, 1800};
        return new long[]{now - 3600, now, 60};
    }

    /** 自定义区间按跨度选择步长，控制单序列点数（目标 ≤1000） */
    private long niceStep(long span) {
        long[] steps = {15, 30, 60, 120, 300, 600, 900, 1800, 3600, 7200, 10800, 21600, 43200, 86400, 172800, 604800};
        for (long step : steps) {
            if (span / step <= 1000) return step;
        }
        return 604800;
    }

    private boolean isAll(String instance) {
        return instance == null || instance.trim().isEmpty() || "all".equals(instance);
    }

    private String instanceSelector(String instance) {
        return isAll(instance) ? "" : "instance=\"" + escape(instance.trim()) + "\"";
    }

    private String sel(String... parts) {
        List<String> list = new ArrayList<String>();
        for (String p : parts) if (p != null && !p.trim().isEmpty()) list.add(p);
        if (list.isEmpty()) return "";
        return "{" + String.join(",", list) + "}";
    }

    private String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String stripPort(String instance) {
        int idx = instance.lastIndexOf(':');
        return idx > 0 ? instance.substring(0, idx) : instance;
    }

    private Double parseValue(String text) {
        try {
            if (text == null || text.isEmpty()) return null;
            double d = Double.parseDouble(text);
            if (Double.isNaN(d) || Double.isInfinite(d)) return null;
            return d;
        } catch (Exception e) {
            return null;
        }
    }

    private Double round(Double v) {
        if (v == null) return null;
        return Math.round(v * 10.0) / 10.0;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asSeries(Object o) {
        if (o instanceof List) return (List<Map<String, Object>>) o;
        return Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    private List<Long> asLongList(Object o) {
        if (o instanceof List) return (List<Long>) o;
        return new ArrayList<Long>();
    }
}