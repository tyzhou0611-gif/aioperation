package com.aiops.config;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置
 * 功能：
 * 1. 将 / 重定向到 /index.html
 * 2. 非API请求全部转发至 /index.html（支持Vue History路由模式）
 * 3. 静态资源映射
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 静态资源映射，优先从classpath:/static/寻找
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true);
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // 访问 / 转发到 index.html
        registry.addViewController("/").setViewName("forward:/index.html");
    }

    /**
     * Vue History 路由支持过滤器
     * 对于非 /api/、非静态资源、非H2 Console的路径，转发到 /index.html
     * 由Vue Router在前端处理路由
     */
    @Bean
    public FilterRegistrationBean<VueRoutingFilter> vueRoutingFilter() {
        FilterRegistrationBean<VueRoutingFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new VueRoutingFilter());
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return registration;
    }

    /**
     * Vue History 路由过滤器实现
     */
    private static class VueRoutingFilter implements Filter {

        private static final String[] SKIPPED_PREFIXES = {
                "/api", "/h2-console", "/static/", "/assets/"
        };

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws java.io.IOException, ServletException {

            HttpServletRequest httpRequest = (HttpServletRequest) request;
            String path = httpRequest.getRequestURI();
            String contextPath = httpRequest.getContextPath();

            // 去除context path后处理
            if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
                path = path.substring(contextPath.length());
            }

            // 跳过API、后端资源路径
            for (String prefix : SKIPPED_PREFIXES) {
                if (path.startsWith(prefix)) {
                    chain.doFilter(request, response);
                    return;
                }
            }

            // 跳过根路径、index.html、带文件扩展名的路径（.js, .css, .png等）
            if (path.equals("/") || path.equals("/index.html") || path.contains(".")) {
                chain.doFilter(request, response);
                return;
            }

            // 对Vue路由路径，转发到index.html
            httpRequest.getRequestDispatcher("/index.html").forward(request, response);
        }
    }
}