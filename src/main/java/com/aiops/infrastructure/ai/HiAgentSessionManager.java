package com.aiops.infrastructure.ai;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.cookie.BasicClientCookie;
import org.apache.hc.client5.http.cookie.Cookie;
import org.apache.hc.core5.http.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HiAgent 会话管理器（非 Spring Bean，由 AIClientFactory 按当前配置动态创建/销毁）。
 * GET 聊天页面 → 收集 Cookie(_csrf/x-csrf-token/tenant) + 提取 x-csrf-token + 尽力提取 app-visitor-key。
 * 惰性 + 容错；支持 invalidate() 主动失效以便热切换。
 */
public class HiAgentSessionManager {

    private static final Logger log = LoggerFactory.getLogger(HiAgentSessionManager.class);
    private static final long TTL_MS = 50 * 60 * 1000; // 会话缓存 50 分钟
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0 Safari/537.36";

    private final String baseUrl;
    private final String chatPage;
    private final String configVisitorKey;
    private final String staticCookie;
    private final String staticCsrf;

    private final CloseableHttpClient http;
    private final java.util.concurrent.CopyOnWriteArrayList<Cookie> cookieJar =
            new java.util.concurrent.CopyOnWriteArrayList<Cookie>();

    private volatile String csrfToken;
    private volatile String visitorKey;
    private volatile long lastInit = 0;

    public HiAgentSessionManager(String baseUrl, String chatPage, String visitorKey,
                                 String staticCookie, String staticCsrf) {
        this.baseUrl = trimEnd(baseUrl);
        this.chatPage = chatPage;
        this.configVisitorKey = visitorKey;
        this.staticCookie = staticCookie;
        this.staticCsrf = staticCsrf;
        this.http = HttpClients.custom()
                .evictExpiredConnections()
                .evictIdleConnections(org.apache.hc.core5.util.TimeValue.ofSeconds(30))
                .build();
        // 若配置了抓包 cookie+csrf，直接采用（跳过握手，适合复用浏览器已登录会话）
        if (staticCsrf != null && !staticCsrf.trim().isEmpty()) {
            this.csrfToken = staticCsrf.trim();
            this.lastInit = System.currentTimeMillis();
            log.info("HiAgent 使用配置的抓包 csrf（跳过自动握手）");
        }
    }

    public boolean configured() { return baseUrl != null && !baseUrl.trim().isEmpty(); }

    /** 确保会话有效；失败不抛异常（返回是否成功）。 */
    public synchronized boolean ensureSession() {
        if (!configured()) return false;
        long now = System.currentTimeMillis();
        if (csrfToken != null && now - lastInit < TTL_MS) return true;
        return doInit();
    }

    public synchronized boolean refresh() {
        lastInit = 0; csrfToken = null; cookieJar.clear();
        return doInit();
    }

    private boolean doInit() {
        // 抓包模式：配了静态 csrf 就直接采用，不做自动握手
        if (staticCsrf != null && !staticCsrf.trim().isEmpty()) {
            this.csrfToken = staticCsrf.trim();
            this.lastInit = System.currentTimeMillis();
            return true;
        }
        try {
            String url = baseUrl + chatPage;
            HttpGet get = new HttpGet(url);
            get.setHeader("User-Agent", UA);
            get.setHeader("Accept", "text/html,application/json");
            try (CloseableHttpResponse resp = http.execute(get)) {
                // 收集 Set-Cookie
                for (Header h : resp.getHeaders("Set-Cookie")) {
                    parseCookie(h.getValue());
                }
                // 兜底：从响应的 cookie 头
                Header sc = resp.getFirstHeader("Set-Cookie");
                if (sc == null) {
                    // HttpClient5 已自动存入内部 store，但我们手动解析了头部，这里确保 csrf
                }
                // 读取 HTML 提取 visitor-key（尽力）
                String html = readBody(resp);
                if (visitorKey == null || visitorKey.isEmpty()) {
                    Matcher mv = Pattern.compile("app-visitor-key[\"'\\s:=]+([A-Za-z0-9_\\-\\.]{8,})").matcher(html);
                    if (mv.find()) visitorKey = mv.group(1);
                }
            }
            // 从 cookieJar 取 x-csrf-token / _csrf
            csrfToken = getCookieValue("x-csrf-token");
            if (csrfToken == null) csrfToken = getCookieValue("_csrf");
            lastInit = System.currentTimeMillis();
            boolean ok = csrfToken != null && !csrfToken.isEmpty();
            if (ok) log.info("HiAgent 会话初始化成功，csrf 已获取");
            else log.warn("HiAgent 会话初始化：未获取到 x-csrf-token（可能需手动配置或网络不通），AI 将降级");
            return ok;
        } catch (Exception e) {
            log.warn("HiAgent 会话初始化失败（不影响启动，AI 降级）: {}", e.getMessage());
            return false;
        }
    }

    public String getCookieHeader() {
        if (staticCookie != null && !staticCookie.trim().isEmpty()) return staticCookie.trim();
        StringBuilder sb = new StringBuilder();
        for (Cookie c : cookieJar) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(c.getName()).append('=').append(c.getValue());
        }
        return sb.toString();
    }

    public String getCsrfToken() { return csrfToken; }

    /** 暴露底层 HttpClient 执行（供 HiAgentClient 发起聊天 POST） */
    public org.apache.hc.client5.http.impl.classic.CloseableHttpResponse execute(
            org.apache.hc.core5.http.ClassicHttpRequest req) throws java.io.IOException {
        return http.execute(req);
    }

    public String getVisitorKey() {
        if (configVisitorKey != null && !configVisitorKey.trim().isEmpty()) return configVisitorKey;
        return visitorKey;
    }

    public boolean isCsrfValid() { return csrfToken != null && !csrfToken.isEmpty(); }

    private String getCookieValue(String name) {
        for (Cookie c : cookieJar) if (name.equalsIgnoreCase(c.getName())) return c.getValue();
        return null;
    }

    private void parseCookie(String raw) {
        if (raw == null) return;
        String[] parts = raw.split(";");
        if (parts.length == 0) return;
        String[] nv = parts[0].split("=", 2);
        if (nv.length < 2) return;
        BasicClientCookie ck = new BasicClientCookie(nv[0].trim(), nv[1].trim());
        // 去重
        for (int i = 0; i < cookieJar.size(); i++) {
            if (cookieJar.get(i).getName().equalsIgnoreCase(nv[0].trim())) { cookieJar.set(i, ck); return; }
        }
        cookieJar.add(ck);
    }

    private String readBody(CloseableHttpResponse resp) throws Exception {
        if (resp.getEntity() == null) return "";
        BufferedReader br = new BufferedReader(
                new InputStreamReader(resp.getEntity().getContent(), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[4096]; int n;
        while ((n = br.read(buf)) != -1) sb.append(buf, 0, n);
        return sb.toString();
    }

    private String trimEnd(String s) {
        if (s == null) return "";
        String t = s.trim();
        return t.endsWith("/") ? t.substring(0, t.length() - 1) : t;
    }

    /** 主动失效（配置变更/重登录时），下次 ensureSession 会重新握手 */
    public synchronized void invalidate() {
        cookieJar.clear();
        this.csrfToken = (staticCsrf != null && !staticCsrf.trim().isEmpty()) ? staticCsrf.trim() : null;
        this.lastInit = 0;
    }

    public void close() { try { http.close(); } catch (Exception ignore) { } }
}