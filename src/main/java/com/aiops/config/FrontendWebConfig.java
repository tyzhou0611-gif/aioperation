package com.aiops.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 可选的前端静态资源托管（用于没有 Nginx 的单进程部署）。
 *
 * 配置 app.frontend.dir=./web 后：
 * - /assets/**、index.html、favicon 从该目录读取
 * - 非 API、无扩展名的路径回退到 index.html，支持 Vue History 路由
 * 未配置时不启用，保持纯 API 服务。
 */
@Configuration
public class FrontendWebConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(FrontendWebConfig.class);

    private final String frontendDir;
    private final boolean enabled;

    public FrontendWebConfig(@Value("${app.frontend.dir:}") String dir) {
        this.frontendDir = normalize(dir);
        this.enabled = this.frontendDir != null;
        if (enabled) log.info("前端静态资源托管已启用: {}", frontendDir);
    }

    private String normalize(String dir) {
        if (dir == null || dir.trim().isEmpty()) return null;
        try {
            Path p = Paths.get(dir.trim()).toAbsolutePath().normalize();
            if (Files.isDirectory(p) && Files.exists(p.resolve("index.html"))) return p.toString();
            log.warn("app.frontend.dir 无效（目录不存在或缺少 index.html）: {}", p);
        } catch (Exception e) {
            log.warn("app.frontend.dir 解析失败: {}", e.getMessage());
        }
        return null;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        if (!enabled) return;
        String base = Paths.get(frontendDir).toUri().toString();
        if (!base.endsWith("/")) base = base + "/";
        registry.addResourceHandler("/assets/**")
                .addResourceLocations(base + "assets/")
                .setCachePeriod(3600);
        registry.addResourceHandler("/index.html", "/favicon.ico", "/vite.svg")
                .addResourceLocations(base)
                .setCachePeriod(0);
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        if (enabled) registry.addViewController("/").setViewName("forward:/index.html");
    }

    @Bean
    public FilterRegistrationBean<FrontendSpaFilter> frontendSpaFilter() {
        FilterRegistrationBean<FrontendSpaFilter> reg = new FilterRegistrationBean<FrontendSpaFilter>();
        reg.setFilter(new FrontendSpaFilter());
        reg.addUrlPatterns("/*");
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        reg.setEnabled(enabled);
        return reg;
    }

    /** Vue History 路由回退：只处理 GET/HEAD、无扩展名、非后端路径 */
    private static class FrontendSpaFilter implements Filter {
        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            HttpServletRequest req = (HttpServletRequest) request;
            String method = req.getMethod();
            if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
                chain.doFilter(request, response);
                return;
            }
            String path = req.getRequestURI();
            String ctx = req.getContextPath();
            if (ctx != null && !ctx.isEmpty() && path.startsWith(ctx)) {
                path = path.substring(ctx.length());
            }
            if (path.startsWith("/api") || path.startsWith("/ws") || path.startsWith("/actuator") || path.startsWith("/h2-console")
                    || path.startsWith("/assets") || path.startsWith("/public") || path.startsWith("/avatar")) {
                chain.doFilter(request, response);
                return;
            }
            if (path.equals("/") || path.equals("/index.html") || path.contains(".")) {
                chain.doFilter(request, response);
                return;
            }
            req.getRequestDispatcher("/index.html").forward(request, response);
        }
    }
}