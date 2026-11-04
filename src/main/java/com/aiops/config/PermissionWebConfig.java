package com.aiops.config;

import com.aiops.security.ApiPermissionInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 注册写接口权限拦截器。
 */
@Configuration
@RequiredArgsConstructor
public class PermissionWebConfig implements WebMvcConfigurer {

    private final ApiPermissionInterceptor apiPermissionInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(apiPermissionInterceptor).addPathPatterns("/api/**");
    }
}