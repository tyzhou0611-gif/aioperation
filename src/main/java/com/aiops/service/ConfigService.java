package com.aiops.service;

import com.aiops.entity.SysConfig;
import com.aiops.repository.SysConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 动态配置服务：内存缓存 + sys_config 持久化。
 * 首次启动以 application.yml / 环境变量为默认值 seed 入库；
 * 之后 setString 即时更新 DB + 缓存，运行时热生效（配合 Factory.refresh）。
 * 优先级：sys_config(DB) > application.yml(默认) > 代码默认。
 */
@Service
public class ConfigService {

    private static final Logger log = LoggerFactory.getLogger(ConfigService.class);

    private final SysConfigRepository repo;

    /** application.yml 默认值（作为初始 seed，不覆盖已在 DB 的值） */
    private final Map<String, String> defaults = new LinkedHashMap<String, String>();

    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<String, String>();

    public ConfigService(SysConfigRepository repo,
                         @Value("${app.ai.provider:none}") String provider,
                         @Value("${app.ai.api-key:}") String apiKey,
                         @Value("${app.ai.model:deepseek-chat}") String model,
                         @Value("${app.ai.api-url:https://api.deepseek.com/v1/chat/completions}") String apiUrl,
                         @Value("${app.ai.hiagent.base-url:}") String hiBaseUrl,
                         @Value("${app.ai.hiagent.app-id:}") String hiAppId,
                         @Value("${app.ai.hiagent.chat-path:/api/bypass/app/}") String hiChatPath,
                         @Value("${app.ai.hiagent.version:2023-08-01}") String hiVersion,
                         @Value("${app.ai.hiagent.action:ChatQueryHomeBot}") String hiAction,
                         @Value("${app.ai.hiagent.chat-page:/product/llm/llm-chat}") String hiChatPage,
                         @Value("${app.ai.hiagent.timeout:60000}") String hiTimeout,
                         @Value("${app.ai.hiagent.visitor-key:}") String hiVisitorKey,
                         @Value("${app.ai.hiagent.cookie:}") String hiCookie,
                         @Value("${app.ai.hiagent.csrf:}") String hiCsrf) {
        this.repo = repo;
        defaults.put("ai.provider", provider);
        defaults.put("ai.api-key", apiKey);
        defaults.put("ai.model", model);
        defaults.put("ai.apiUrl", apiUrl);
        defaults.put("ai.hiagent.base-url", hiBaseUrl);
        defaults.put("ai.hiagent.app-id", hiAppId);
        defaults.put("ai.hiagent.chat-path", hiChatPath);
        defaults.put("ai.hiagent.version", hiVersion);
        defaults.put("ai.hiagent.action", hiAction);
        defaults.put("ai.hiagent.chat-page", hiChatPage);
        defaults.put("ai.hiagent.timeout", hiTimeout);
        defaults.put("ai.hiagent.visitor-key", hiVisitorKey);
        defaults.put("ai.hiagent.cookie", hiCookie);
        defaults.put("ai.hiagent.csrf", hiCsrf);
    }

    @PostConstruct
    public void reload() {
        cache.clear();
        for (SysConfig c : repo.findAll()) {
            if (c.getConfigKey() != null) cache.put(c.getConfigKey(), c.getConfigValue() == null ? "" : c.getConfigValue());
        }
        // seed 缺失项（application.yml 默认值）入库，保证升级不丢配置
        for (Map.Entry<String, String> e : defaults.entrySet()) {
            if (!cache.containsKey(e.getKey())) {
                String v = e.getValue() == null ? "" : e.getValue();
                upsert(e.getKey(), v, group(e.getKey()));
                cache.put(e.getKey(), v);
            }
        }
        log.info("ConfigService 已加载 {} 项配置", cache.size());
    }

    public String getString(String key, String defaultValue) {
        String v = cache.get(key);
        if (v == null) { v = defaults.get(key); }
        return v == null ? defaultValue : v;
    }

    public Map<String, String> getAllByGroup(String group) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> e : cache.entrySet()) {
            if (group == null || group.equalsIgnoreCase(group(e.getKey()))) out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    /** 更新 DB + 缓存；返回是否变化。 */
    @Transactional
    public boolean setString(String key, String value) {
        String v = value == null ? "" : value;
        String old = cache.get(key);
        upsert(key, v, group(key));
        cache.put(key, v);
        return old == null ? !v.isEmpty() : !v.equals(old);
    }

    @Transactional
    public void setMany(Map<String, String> kv) {
        for (Map.Entry<String, String> e : kv.entrySet()) {
            if (e.getKey() == null) continue;
            setString(e.getKey(), e.getValue());
        }
    }

    private void upsert(String key, String value, String group) {
        SysConfig sc = repo.findByConfigKey(key);
        if (sc == null) {
            sc = new SysConfig();
            sc.setConfigKey(key);
        }
        sc.setConfigValue(value);
        sc.setConfigGroup(group);
        repo.save(sc);
    }

    private String group(String key) {
        if (key.startsWith("ai.")) return "AI";
        if (key.startsWith("metrics.") || key.startsWith("prometheus.") || key.startsWith("grafana.") || key.startsWith("log.")) return "METRICS";
        if (key.startsWith("notify.") || key.startsWith("mail.")) return "MAIL";
        return "GENERAL";
    }
}