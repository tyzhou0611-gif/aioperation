package com.aiops.config;

import java.util.Arrays;
import com.aiops.common.Result;
import com.aiops.security.JwtAuthFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Spring Security 配置
 *
 * 安全策略：
 * - JWT 无状态认证（不创建 Session）
 * - 禁用 CSRF（API 使用 Token 认证）
 * - CORS 允许前端 localhost:5173 开发服务器调试
 * - 认证失败返回 JSON（不重定向到登录页）
 * - 白名单路径：/api/auth/login, /doc.html, /static/**, /h2-console/**
 *
 * 拦截规则：
 * - /api/auth/login  —— 公开（登录接口）
 * - /doc.html        —— 公开（接口文档，预留 Knife4j/Swagger）
 * - /static/**       —— 公开（前端静态资源）
 * - /h2-console/**   —— 公开（开发期数据库控制台）
 * - 其余 /api/**     —— 需要 JWT 认证
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final ObjectMapper objectMapper;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // ====== 1. 禁用 CSRF ======
                .csrf(AbstractHttpConfigurer::disable)

                // ====== 2. CORS 跨域 ======
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))

                // ====== 3. 无状态会话 ======
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // ====== 4. 请求权限配置 ======
                .authorizeHttpRequests(auth -> auth
                        // 白名单路径
                        .antMatchers("/", "/index.html", "/favicon.ico").permitAll()
                        .antMatchers("/assets/**").permitAll()
                        .antMatchers("/error").permitAll()
                        // 登录接口
                        .antMatchers("/api/auth/login").permitAll()
                        // Actuator 端点（Prometheus 抓取）
                        .antMatchers("/actuator/health", "/actuator/prometheus", "/actuator/info").permitAll()
                        // 系统健康检查（无需认证）
                        .antMatchers("/api/system/health").permitAll()
                        // 接口文档（预留 Swagger / Knife4j）
                        .antMatchers("/doc.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        // Grafana 同源代理（iframe 无法携带 JWT 头，交由 Grafana 自身登录鉴权）
                        .antMatchers("/api/grafana/**").permitAll()
                        // Grafana 前端绝对路径资源（/public/**、/avatar/**）经代理转发
                        .antMatchers("/public/**", "/avatar/**").permitAll()
                        // H2 Console
                        .antMatchers("/h2-console/**").permitAll()
                        // 其余所有请求需认证
                        .anyRequest().authenticated()
                )

                // ====== 5. 异常处理 ======
                .exceptionHandling(ex -> ex
                        // 未认证 → 返回 JSON 401
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                            String json = objectMapper.writeValueAsString(
                                    Result.unauthorized("未授权，请先登录"));
                            response.getWriter().write(json);
                        })
                        // 已认证但无权限 → 返回 JSON 403
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                            String json = objectMapper.writeValueAsString(
                                    Result.error(403, "权限不足，无法访问"));
                            response.getWriter().write(json);
                        })
                )

                // ====== 6. H2 Console 允许 frame ======
                .headers(headers ->
                        headers.frameOptions(HeadersConfigurer.FrameOptionsConfig::sameOrigin))

                // ====== 7. JWT 过滤器 ======
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * CORS 跨域配置
     * 允许 Vue 开发服务器（localhost:5173）及生产环境访问
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        // 明确允许前端开发服务器和生产环境域名
        configuration.setAllowedOriginPatterns(Arrays.asList(
                "http://localhost:*",
                "http://127.0.0.1:*",
                "https://*"
        ));
        configuration.setAllowedMethods(Arrays.asList(
                HttpMethod.GET.name(),
                HttpMethod.POST.name(),
                HttpMethod.PUT.name(),
                HttpMethod.DELETE.name(),
                HttpMethod.PATCH.name(),
                HttpMethod.OPTIONS.name()
        ));
        configuration.setAllowedHeaders(Arrays.asList(
                "Authorization",
                "Content-Type",
                "X-Requested-With",
                "Accept",
                "Origin",
                "Cache-Control"
        ));
        configuration.setExposedHeaders(Arrays.asList("Authorization"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}