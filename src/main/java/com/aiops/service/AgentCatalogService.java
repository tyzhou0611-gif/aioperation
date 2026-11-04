package com.aiops.service;

import com.aiops.common.ServiceException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Prometheus Exporter 目录：从 classpath:agents/exporters.json 加载 */
@Service
public class AgentCatalogService {

    private static final Logger log = LoggerFactory.getLogger(AgentCatalogService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile List<Map<String, Object>> catalog = new ArrayList<Map<String, Object>>();

    @PostConstruct
    public void load() {
        try (InputStream in = new ClassPathResource("agents/exporters.json").getInputStream()) {
            catalog = mapper.readValue(in, new TypeReference<List<Map<String, Object>>>() {});
            log.info("Exporter 目录已加载: {} 个", catalog.size());
        } catch (Exception e) {
            log.warn("Exporter 目录加载失败: {}", e.getMessage());
            catalog = new ArrayList<Map<String, Object>>();
        }
    }

    public List<Map<String, Object>> list() {
        return Collections.unmodifiableList(catalog);
    }

    public Map<String, Object> get(String key) {
        if (key == null) throw new ServiceException(400, "Exporter 不能为空");
        for (Map<String, Object> item : catalog) {
            if (key.equalsIgnoreCase(String.valueOf(item.get("key")))) return item;
        }
        throw new ServiceException(404, "不支持的 Exporter: " + key);
    }

    public static String str(Map<String, Object> m, String key, String def) {
        Object v = m == null ? null : m.get(key);
        return v == null || String.valueOf(v).trim().isEmpty() ? def : String.valueOf(v).trim();
    }

    public static int integer(Map<String, Object> m, String key, int def) {
        Object v = m == null ? null : m.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        try { return Integer.parseInt(String.valueOf(v)); } catch (Exception e) { return def; }
    }

    public static Map<String, Object> desc(String key, String name, int port, String binary) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("key", key); m.put("name", name); m.put("port", port); m.put("binary", binary);
        return m;
    }
}