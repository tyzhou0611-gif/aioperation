package com.aiops.service;

import com.aiops.entity.SysConfig;
import com.aiops.repository.SysConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 动态配置服务：内存缓存 + sys_config 持久化。
 * 首次启动以 application.yml / 环境变量为默认值 seed 入库；
 * 之后 setString 即时更新 DB + 缓存，运行时热生效（配合 Factory.refresh）。
 * 优先级：sys_config(DB) > application.yml(默认) > 代码默认。
 *
 * 敏感配置（SECRET_KEYS）在入库前自动 AES 加密，读取时透明解密；
 * 历史明文值会在启动时自动迁移为密文。
 */
@Service
public class ConfigService {

    private static final Logger log = LoggerFactory.getLogger(ConfigService.class);

    /** 需要加密存储的配置项 */
    private static final Set<String> SECRET_KEYS = new HashSet<String>(Arrays.asList(
            "ai.api-key",
            "ai.hiagent.openapi-key",
            "ai.hiagent.cookie",
            "ai.hiagent.csrf",
            "ai.hiagent.visitor-key"));

    private final SysConfigRepository repo;
    private final PasswordEncryptor encryptor;

    /** application.yml 默认值（作为初始 seed，不覆盖已在 DB 的值） */
    private final Map<String, String> defaults = new LinkedHashMap<String, String>();

    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<String, String>();

    private final boolean aiBootstrapEnabled;
    private final String aiBootstrapVersion;
    private final String aiBootstrapProvider;
    private final String aiBootstrapUrl;
    private final String aiBootstrapKey;
    private final String aiBootstrapModel;

    public ConfigService(SysConfigRepository repo,
                         PasswordEncryptor encryptor,
                         @Value("${app.ai.provider:none}") String provider,
                         @Value("${app.ai.api-key:}") String apiKey,
                         @Value("${app.ai.model:deepseek-chat}") String model,
                         @Value("${app.ai.api-url:https://api.deepseek.com/v1/chat/completions}") String apiUrl,
                         @Value("${app.ai.anthropic.version:2023-06-01}") String anthropicVersion,
                         @Value("${app.ai.anthropic.max-tokens:4096}") String anthropicMaxTokens,
                         @Value("${app.ai.bootstrap.enabled:false}") boolean aiBootstrapEnabled,
                         @Value("${app.ai.bootstrap.version:}") String aiBootstrapVersion,
                         @Value("${app.ai.bootstrap.provider:anthropic}") String aiBootstrapProvider,
                         @Value("${app.ai.bootstrap.api-url:}") String aiBootstrapUrl,
                         @Value("${app.ai.bootstrap.api-key:}") String aiBootstrapKey,
                         @Value("${app.ai.bootstrap.model:}") String aiBootstrapModel,
                         @Value("${app.ai.hiagent.base-url:}") String hiBaseUrl,
                         @Value("${app.ai.hiagent.app-id:}") String hiAppId,
                         @Value("${app.ai.hiagent.mode:openapi}") String hiMode,
                         @Value("${app.ai.hiagent.openapi-url:}") String hiOpenapiUrl,
                         @Value("${app.ai.hiagent.openapi-key:}") String hiOpenapiKey,
                         @Value("${app.ai.hiagent.user-id:aiops}") String hiUserId,
                         @Value("${app.ai.hiagent.chat-path:/api/bypass/app/}") String hiChatPath,
                         @Value("${app.ai.hiagent.version:2023-08-01}") String hiVersion,
                         @Value("${app.ai.hiagent.action:ChatQueryHomeBot}") String hiAction,
                         @Value("${app.ai.hiagent.chat-page:/product/llm/llm-chat}") String hiChatPage,
                         @Value("${app.ai.hiagent.timeout:60000}") String hiTimeout,
                         @Value("${app.ai.hiagent.visitor-key:}") String hiVisitorKey,
                         @Value("${app.ai.hiagent.cookie:}") String hiCookie,
                         @Value("${app.ai.hiagent.csrf:}") String hiCsrf,
                         @Value("${app.metrics.source:prometheus}") String metricsSource,
                         @Value("${app.prometheus.url:}") String prometheusUrl,
                         @Value("${app.prometheus.mode:embedded}") String prometheusMode,
                         @Value("${app.grafana.url:}") String grafanaUrl,
                         @Value("${app.log.source:default}") String logSource,
                         @Value("${app.server.status-source:auto}") String serverStatusSource) {
        this.repo = repo;
        this.encryptor = encryptor;
        this.aiBootstrapEnabled = aiBootstrapEnabled;
        this.aiBootstrapVersion = aiBootstrapVersion;
        this.aiBootstrapProvider = aiBootstrapProvider;
        this.aiBootstrapUrl = aiBootstrapUrl;
        this.aiBootstrapKey = aiBootstrapKey;
        this.aiBootstrapModel = aiBootstrapModel;
        defaults.put("ai.provider", provider);
        defaults.put("ai.api-key", apiKey);
        defaults.put("ai.model", model);
        defaults.put("ai.apiUrl", apiUrl);
        defaults.put("ai.anthropic.version", anthropicVersion);
        defaults.put("ai.anthropic.max-tokens", anthropicMaxTokens);
        defaults.put("ai.hiagent.base-url", hiBaseUrl);
        defaults.put("ai.hiagent.app-id", hiAppId);
        defaults.put("ai.hiagent.mode", hiMode);
        defaults.put("ai.hiagent.openapi-url", hiOpenapiUrl);
        defaults.put("ai.hiagent.openapi-key", hiOpenapiKey);
        defaults.put("ai.hiagent.user-id", hiUserId);
        defaults.put("ai.hiagent.chat-path", hiChatPath);
        defaults.put("ai.hiagent.version", hiVersion);
        defaults.put("ai.hiagent.action", hiAction);
        defaults.put("ai.hiagent.chat-page", hiChatPage);
        defaults.put("ai.hiagent.timeout", hiTimeout);
        defaults.put("ai.hiagent.visitor-key", hiVisitorKey);
        defaults.put("ai.hiagent.cookie", hiCookie);
        defaults.put("ai.hiagent.csrf", hiCsrf);
        defaults.put("metrics.source", metricsSource);
        defaults.put("prometheus.url", prometheusUrl);
        defaults.put("prometheus.mode", prometheusMode);
        defaults.put("grafana.url", grafanaUrl);
        defaults.put("log.source", logSource);
        defaults.put("server.status.source", serverStatusSource);
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
        migrateSecrets();
        log.info("ConfigService 已加载 {} 项配置（敏感项已加密存储）", cache.size());
        applyAiBootstrap();
    }

    /** 历史明文敏感配置 → 密文（密钥变更导致无法解密时同样会重新加密） */
    private void migrateSecrets() {
        for (String key : SECRET_KEYS) {
            String v = cache.get(key);
            if (v == null || v.isEmpty() || encryptor.isEncrypted(v)) continue;
            if (encryptor.looksEncrypted(v)) {
                // 已是密文但当前 AES_KEY 解不开：保留原值，交由使用方提示重新设置，绝不二次加密
                log.warn("配置项 {} 已是密文但当前 AES_KEY 无法解密，请重新设置（未自动覆盖）", key);
                continue;
            }
            String enc = encryptor.encrypt(v);
            upsert(key, enc, group(key));
            cache.put(key, enc);
            log.info("配置项 {} 已迁移为加密存储", key);
        }
    }

    /**
     * 一次性把 AI 配置引导到新的 Anthropic 网关。
     * 通过独立的 app.ai.bootstrap.* 配置，避免被服务器上旧 application.yml 的 app.ai.* 覆盖。
     * 执行一次后写入 ai.bootstrap.version，之后页面上的手动配置不会被重启覆盖。
     */
    private void applyAiBootstrap() {
        if (!aiBootstrapEnabled) return;
        if (isBlank(aiBootstrapVersion) || isBlank(aiBootstrapUrl) || isBlank(aiBootstrapKey)) {
            log.warn("AI bootstrap 配置不完整，跳过迁移");
            return;
        }
        if (aiBootstrapVersion.equals(cache.get("ai.bootstrap.version"))) return;

        setString("ai.provider", isBlank(aiBootstrapProvider) ? "anthropic" : aiBootstrapProvider);
        setString("ai.apiUrl", aiBootstrapUrl.trim());
        setString("ai.api-key", aiBootstrapKey.trim());
        if (!isBlank(aiBootstrapModel)) setString("ai.model", aiBootstrapModel.trim());
        setString("ai.bootstrap.version", aiBootstrapVersion);
        log.info("AI 配置已引导: provider={} url={} model={}",
                cache.get("ai.provider"), cache.get("ai.apiUrl"), cache.get("ai.model"));
    }

    private boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }

    public static boolean isSecretKey(String key) { return key != null && SECRET_KEYS.contains(key); }

    /** 读取配置；敏感项自动解密（密文损坏时回退原值并告警） */
    public String getString(String key, String defaultValue) {
        String v = cache.get(key);
        boolean fromStore = v != null;
        if (!fromStore) { v = defaults.get(key); }
        if (v == null) return defaultValue;
        if (fromStore && isSecretKey(key) && !v.isEmpty()) {
            try {
                return encryptor.decrypt(v);
            } catch (Exception e) {
                log.warn("配置项 {} 解密失败（AES 密钥可能已变更），请重新设置：{}", key, e.getMessage());
                return v;
            }
        }
        return v;
    }

    /** 读取原始存储值（内部诊断用，可能是密文） */
    public String getStored(String key) { return cache.get(key); }

    public Map<String, String> getAllByGroup(String group) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> e : cache.entrySet()) {
            if (group == null || group.equalsIgnoreCase(group(e.getKey()))) out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    /** 更新 DB + 缓存；敏感项自动加密存储。返回明文是否变化。 */
    @Transactional
    public boolean setString(String key, String value) {
        String v = value == null ? "" : value;
        String stored = (isSecretKey(key) && !v.isEmpty()) ? encryptor.encrypt(v) : v;
        String old = cache.get(key);
        upsert(key, stored, group(key));
        cache.put(key, stored);
        String oldPlain = old;
        if (oldPlain != null && isSecretKey(key) && !oldPlain.isEmpty()) {
            try { oldPlain = encryptor.decrypt(oldPlain); } catch (Exception ignore) { }
        }
        return oldPlain == null ? !v.isEmpty() : !v.equals(oldPlain);
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