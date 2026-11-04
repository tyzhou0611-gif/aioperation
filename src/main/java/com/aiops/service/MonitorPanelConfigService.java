package com.aiops.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 监控面板配置（组件显隐 / 自定义重点指标）。
 *
 * 持久化在 sys_config.monitor.panel.config（JSON），页面保存后即时生效。
 * 结构：
 * {
 *   "version": 1,
 *   "cards": { "totalHosts": true, "onlineHosts": true, ... },
 *   "charts": { "cpu": {"enabled":true,"span":12}, ... },
 *   "hostTable": { "enabled": true },
 *   "customMetrics": [ {id,name,query,unit,type,span,color,highlight,enabled,description} ]
 * }
 */
@Service
public class MonitorPanelConfigService {

    private static final Logger log = LoggerFactory.getLogger(MonitorPanelConfigService.class);
    public static final String KEY = "monitor.panel.config";
    private static final int MAX_METRICS = 20;
    private static final int MAX_QUERY = 500;
    private static final int MAX_NAME = 40;

    private static final String[] CARD_KEYS = {
            "totalHosts", "onlineHosts", "offlineHosts", "avgCpu", "avgMemory", "maxDisk"
    };
    private static final String[] CHART_KEYS = {"cpu", "memory", "disk", "network", "load"};
    private static final Set<String> UNITS = new LinkedHashSet<String>(
            java.util.Arrays.asList("pct", "bps", "bytes", "num", "ms", "count"));
    private static final Set<String> TYPES = new LinkedHashSet<String>(
            java.util.Arrays.asList("line", "area", "bar"));

    private final ConfigService config;
    private final ObjectMapper mapper = new ObjectMapper();

    public MonitorPanelConfigService(ConfigService config) {
        this.config = config;
    }

    /** 当前生效配置（损坏时回落默认值） */
    public Map<String, Object> get() {
        String raw = config.getString(KEY, "");
        if (raw == null || raw.trim().isEmpty()) return defaults();
        try {
            Object parsed = mapper.readValue(raw, Object.class);
            if (parsed instanceof Map) return normalize((Map<String, Object>) parsed, false);
        } catch (Exception e) {
            log.warn("监控面板配置解析失败，使用默认配置: {}", e.getMessage());
        }
        return defaults();
    }

    /** 保存并返回归一化后的配置 */
    public Map<String, Object> save(Map<String, Object> body) throws Exception {
        Map<String, Object> cfg = normalize(body, true);
        config.setString(KEY, mapper.writeValueAsString(cfg));
        log.info("监控面板配置已更新: charts={} customMetrics={}",
                cfg.get("charts"), ((List<?>) cfg.get("customMetrics")).size());
        return cfg;
    }

    public Map<String, Object> defaults() {
        return normalize(new LinkedHashMap<String, Object>(), false);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> normalize(Map<String, Object> input, boolean strict) {
        Map<String, Object> in = input == null ? new LinkedHashMap<String, Object>() : input;
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("version", 1);
        out.put("cards", normalizeCards((Map<String, Object>) in.get("cards")));
        out.put("charts", normalizeCharts((Map<String, Object>) in.get("charts")));
        out.put("hostTable", normalizeHostTable(in.get("hostTable")));
        out.put("customMetrics", normalizeMetrics((List<Object>) in.get("customMetrics"), strict));
        return out;
    }

    private Map<String, Object> normalizeCards(Map<String, Object> in) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        for (String key : CARD_KEYS) {
            Object v = in == null ? null : in.get(key);
            out.put(key, !(v instanceof Boolean) || (Boolean) v);
        }
        return out;
    }

    private Map<String, Object> normalizeCharts(Map<String, Object> in) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        for (String key : CHART_KEYS) {
            Map<String, Object> chart = new LinkedHashMap<String, Object>();
            Object raw = in == null ? null : in.get(key);
            Map<String, Object> src = raw instanceof Map ? (Map<String, Object>) raw : new LinkedHashMap<String, Object>();
            Object enabled = src.get("enabled");
            chart.put("enabled", !(enabled instanceof Boolean) || (Boolean) enabled);
            int def = "load".equals(key) ? 24 : 12;
            chart.put("span", clampSpan(src.get("span"), def));
            out.put(key, chart);
        }
        return out;
    }

    private Map<String, Object> normalizeHostTable(Object raw) {
        boolean enabled = true;
        if (raw instanceof Boolean) enabled = (Boolean) raw;
        else if (raw instanceof Map) {
            Object e = ((Map<?, ?>) raw).get("enabled");
            if (e instanceof Boolean) enabled = (Boolean) e;
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("enabled", enabled);
        return out;
    }

    private List<Object> normalizeMetrics(List<Object> list, boolean strict) {
        List<Object> out = new ArrayList<Object>();
        if (list == null) return out;
        if (list.size() > MAX_METRICS) {
            if (strict) throw new IllegalArgumentException("自定义指标最多 " + MAX_METRICS + " 个");
            list = list.subList(0, MAX_METRICS);
        }
        Set<String> ids = new LinkedHashSet<String>();
        int index = 0;
        for (Object item : list) {
            index++;
            if (!(item instanceof Map)) continue;
            Map<String, Object> src = (Map<String, Object>) item;
            String query = str(src.get("query"), MAX_QUERY);
            if (query.isEmpty()) {
                if (strict) throw new IllegalArgumentException("第 " + index + " 个自定义指标缺少 PromQL 查询语句");
                continue;
            }
            String name = str(src.get("name"), MAX_NAME);
            if (name.isEmpty()) name = "自定义指标 " + index;
            String id = str(src.get("id"), 40);
            if (id.isEmpty() || ids.contains(id)) {
                id = "cm-" + Integer.toHexString((name + "|" + query).hashCode());
                int n = 1;
                String base = id;
                while (ids.contains(id)) id = base + "-" + (n++);
            }
            ids.add(id);

            String unit = str(src.get("unit"), 10);
            if (!UNITS.contains(unit)) unit = "num";
            String type = str(src.get("type"), 10);
            if (!TYPES.contains(type)) type = "line";

            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("id", id);
            m.put("name", name);
            m.put("query", query);
            m.put("unit", unit);
            m.put("type", type);
            m.put("span", clampSpan(src.get("span"), 12));
            m.put("color", color(src.get("color"), index));
            m.put("highlight", Boolean.TRUE.equals(src.get("highlight")));
            m.put("enabled", !(src.get("enabled") instanceof Boolean) || (Boolean) src.get("enabled"));
            m.put("description", str(src.get("description"), 100));
            out.add(m);
        }
        return out;
    }

    private int clampSpan(Object raw, int def) {
        int span = def;
        if (raw instanceof Number) span = ((Number) raw).intValue();
        else if (raw instanceof String) {
            try { span = Integer.parseInt(((String) raw).trim()); } catch (Exception ignore) { }
        }
        if (span < 6) span = 6;
        if (span > 24) span = 24;
        return span;
    }

    private String color(Object raw, int index) {
        String v = raw == null ? "" : String.valueOf(raw).trim();
        if (v.matches("^#[0-9a-fA-F]{6}$")) return v.toLowerCase();
        String[] palette = {"#409eff", "#67c23a", "#e6a23c", "#f56c6c", "#8e44ad", "#00bcd4",
                "#ff9800", "#3f51b5", "#009688", "#c2185b"};
        return palette[(Math.max(index, 1) - 1) % palette.length];
    }

    private String str(Object raw, int max) {
        String v = raw == null ? "" : String.valueOf(raw).trim();
        return v.length() <= max ? v : v.substring(0, max);
    }
}