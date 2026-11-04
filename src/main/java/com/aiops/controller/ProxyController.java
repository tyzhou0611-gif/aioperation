package com.aiops.controller;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Locale;

/**
 * Grafana 同源反向代理（Java 8 兼容：RestTemplate 实现）。
 * 解决跨域 iframe 第三方 Cookie 被拦截导致登录不跳转的问题。
 */
@RestController
public class ProxyController {

    private static final Logger log = LoggerFactory.getLogger(ProxyController.class);
    private static final String PREFIX = "/api/grafana";

    private final RestTemplate restTemplate;
    private final String grafanaUrl;
    private final boolean configured;

    public ProxyController(@Value("${app.grafana.url:}") String grafanaUrl) {
        this.grafanaUrl = (grafanaUrl == null || grafanaUrl.trim().isEmpty()) ? "" : trimEnd(grafanaUrl.trim());
        this.configured = !this.grafanaUrl.isEmpty();
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(5000);
        f.setReadTimeout(30000);
        this.restTemplate = new RestTemplate(f);
        if (configured) log.info("Grafana 同源代理已启用: {}{}", this.grafanaUrl, PREFIX);
    }

    @RequestMapping(PREFIX + "/**")
    public void proxy(HttpServletRequest req, HttpServletResponse res) {
        if (!configured) { writePlaceholder(res); return; }

        String uri = req.getRequestURI();
        if (uri.equals(req.getContextPath() + PREFIX)) {
            try { res.setStatus(301); res.setHeader("Location", PREFIX + "/"); } catch (Exception ignore) {}
            return;
        }

        try {
            String rest = uri.substring((req.getContextPath() + PREFIX).length());
            if (rest.isEmpty()) rest = "/";
            if (!rest.startsWith("/")) rest = "/" + rest;
            String query = req.getQueryString();
            URI target = URI.create(grafanaUrl + rest + (query != null ? "?" + query : ""));

            byte[] body = readAll(req.getInputStream());
            HttpHeaders headers = new HttpHeaders();
            Enumeration<String> hn = req.getHeaderNames();
            while (hn.hasMoreElements()) {
                String name = hn.nextElement();
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.equals("host") || lower.equals("connection") || lower.equals("content-length")
                        || lower.equals("accept-encoding") || lower.equals("transfer-encoding")
                        || lower.equals("origin") || lower.equals("referer")) continue;
                Enumeration<String> vals = req.getHeaders(name);
                while (vals.hasMoreElements()) headers.add(name, vals.nextElement());
            }
            headers.set("X-Forwarded-Host", req.getHeader("host"));
            headers.set("X-Forwarded-For", req.getRemoteAddr());
            headers.set("X-Forwarded-Proto", req.isSecure() ? "https" : "http");

            HttpMethod m = HttpMethod.resolve(req.getMethod().toUpperCase(Locale.ROOT));
            if (m == null) m = HttpMethod.GET;
            HttpEntity<byte[]> entity = new HttpEntity<>(body.length > 0 ? body : null, headers);
            ResponseEntity<byte[]> g = restTemplate.exchange(target, m, entity, byte[].class);

            res.setStatus(g.getStatusCodeValue());
            MediaType ct = g.getHeaders().getContentType();
            boolean isHtml = ct != null && ct.toString().toLowerCase(Locale.ROOT).contains("text/html");
            if (ct != null) res.setContentType(ct.toString());

            for (String key : g.getHeaders().keySet()) {
                String lower = key.toLowerCase(Locale.ROOT);
                if (lower.equals("content-type") || lower.equals("transfer-encoding") || lower.equals("connection")
                        || lower.equals("content-length") || lower.startsWith("content-encoding")) continue;
                for (String val : g.getHeaders().get(key)) {
                    if (lower.equals("set-cookie")) res.addHeader("Set-Cookie", sanitizeCookie(val));
                    else if (lower.equals("location")) res.addHeader("Location", rewriteLocation(val));
                    else res.addHeader(key, val);
                }
            }

            byte[] out = g.getBody();
            if (isHtml && out != null && out.length > 0) {
                String html = new String(out, StandardCharsets.UTF_8);
                html = html.replaceAll("<base href=\"/[\"']?\\s*/?>", "<base href=\"" + PREFIX + "/\">");
                html = html.replaceAll("<base href='/[\"']?\\s*/?>", "<base href=\"" + PREFIX + "/\">");
                html = html.replaceAll("\"appUrl\":\"https?://[^\"]*\"", "\"appUrl\":\"" + PREFIX + "/\"");
                html = html.replace("\"appSubUrl\":\"\"", "\"appSubUrl\":\"" + PREFIX + "\"");
                html = html.replace("appSubUrl: \"\"", "appSubUrl: \"" + PREFIX + "\"");
                html = html.replace("\"/public/", "\"" + PREFIX + "/public/");
                html = html.replace("\"public/", "\"" + PREFIX + "/public/");
                out = html.getBytes(StandardCharsets.UTF_8);
            }
            if (out != null && out.length > 0) { res.setContentLength(out.length); res.getOutputStream().write(out); }
        } catch (Exception e) {
            log.error("Grafana 代理失败: {} -> {}", req.getRequestURI(), e.toString());
            try {
                res.setStatus(502); res.setContentType(MediaType.TEXT_HTML_VALUE);
                res.getWriter().write("<h3>Grafana 代理失败</h3><pre>" + e.getMessage() + "</pre>");
            } catch (Exception ignored) {}
        }
    }

    private String sanitizeCookie(String cookie) {
        StringBuilder sb = new StringBuilder();
        for (String part : cookie.split(";")) {
            String p = part.trim();
            String low = p.toLowerCase(Locale.ROOT);
            if (low.startsWith("domain=") || low.startsWith("samesite=") || low.equals("secure")) continue;
            if (sb.length() > 0) sb.append("; ");
            sb.append(p);
        }
        return sb.toString();
    }

    private String rewriteLocation(String loc) {
        if (loc == null) return null;
        if (loc.startsWith(grafanaUrl)) return PREFIX + loc.substring(grafanaUrl.length());
        if (loc.startsWith("/") && !loc.startsWith(PREFIX)) return PREFIX + loc;
        return loc;
    }

    private String trimEnd(String s) { return s.endsWith("/") ? s.substring(0, s.length() - 1) : s; }

    private byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int n;
        while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    private void writePlaceholder(HttpServletResponse res) {
        try {
            res.setContentType(MediaType.TEXT_HTML_VALUE); res.setCharacterEncoding("UTF-8");
            res.getWriter().write("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"UTF-8\"><title>Grafana 未配置</title>"
                    + "<style>body{font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;margin:0;background:#f0f2f5}"
                    + ".card{text-align:center;padding:48px;background:#fff;border-radius:12px;box-shadow:0 4px 24px rgba(0,0,0,.1)}</style></head>"
                    + "<body><div class=\"card\"><h2>Grafana 看板</h2><p>未检测到外部 Grafana，请配置 app.grafana.url。</p>"
                    + "<p>当前使用内置 ECharts 图表。</p></div></body></html>");
        } catch (Exception ignore) {}
    }
}
