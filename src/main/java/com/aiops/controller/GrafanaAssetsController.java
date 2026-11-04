package com.aiops.controller;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.Locale;

/**
 * Grafana 绝对路径资源代理（/public/**、/avatar/**），Java 8 兼容（RestTemplate）。
 * 转发到真实 Grafana，返回正确的 JS/CSS/图片 MIME，规避 MIME 不匹配 + nosniff 拦截。
 */
@RestController
public class GrafanaAssetsController {

    private static final Logger log = LoggerFactory.getLogger(GrafanaAssetsController.class);

    private final RestTemplate restTemplate;
    private final String grafanaUrl;
    private final boolean configured;

    public GrafanaAssetsController(@Value("${app.grafana.url:}") String grafanaUrl) {
        String g = grafanaUrl == null ? "" : grafanaUrl.trim();
        this.configured = !g.trim().isEmpty();
        this.grafanaUrl = g.endsWith("/") ? g.substring(0, g.length() - 1) : g;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(5000);
        f.setReadTimeout(30000);
        this.restTemplate = new RestTemplate(f);
    }

    @RequestMapping({"/public/**", "/avatar/**"})
    public void forwardAssets(HttpServletRequest req, HttpServletResponse res) {
        if (!configured) { res.setStatus(404); return; }
        try {
            String path = req.getRequestURI();
            String query = req.getQueryString();
            URI target = URI.create(grafanaUrl + path + (query != null ? "?" + query : ""));
            HttpMethod m = HttpMethod.resolve(req.getMethod().toUpperCase(Locale.ROOT));
            if (m == null) m = HttpMethod.GET;
            HttpHeaders headers = new HttpHeaders();
            String accept = req.getHeader("Accept");
            if (accept != null) headers.set("Accept", accept);
            ResponseEntity<byte[]> g = restTemplate.exchange(target, m, new HttpEntity<byte[]>(headers), byte[].class);

            res.setStatus(g.getStatusCodeValue());
            if (g.getHeaders().getContentType() != null) res.setContentType(g.getHeaders().getContentType().toString());
            String cc = g.getHeaders().getFirst("Cache-Control");
            if (cc != null) res.setHeader("Cache-Control", cc);
            byte[] body = g.getBody();
            if (body != null) { res.setContentLength(body.length); res.getOutputStream().write(body); }
        } catch (Exception e) {
            log.warn("Grafana 资源代理失败 {}: {}", req.getRequestURI(), e.toString());
            res.setStatus(502);
        }
    }
}
