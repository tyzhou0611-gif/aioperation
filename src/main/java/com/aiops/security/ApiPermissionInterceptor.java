package com.aiops.security;

import com.aiops.common.Result;
import com.aiops.service.AccessControlService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * 写操作 API 权限拦截：页面访问由动态菜单控制，这里对主要写接口做后端强制校验，
 * 防止“只读角色”绕过前端直接调用接口。
 *
 * 超级管理员直接放行；未配置规则的接口保持原有“登录即可访问”的行为。
 */
@Component
public class ApiPermissionInterceptor implements HandlerInterceptor {

    private final AccessControlService accessControl;
    private final ObjectMapper objectMapper;
    private final AntPathMatcher matcher = new AntPathMatcher();

    public ApiPermissionInterceptor(AccessControlService accessControl, ObjectMapper objectMapper) {
        this.accessControl = accessControl;
        this.objectMapper = objectMapper;
    }

    private static final class Rule {
        final String method;
        final String pattern;
        final List<String> permissions;
        Rule(String method, String pattern, String... permissions) {
            this.method = method;
            this.pattern = pattern;
            this.permissions = Arrays.asList(permissions);
        }
    }

    private static final List<Rule> RULES = Arrays.asList(
            new Rule("POST", "/api/user", "sys:user:add"),
            new Rule("PUT", "/api/user/*", "sys:user:edit"),
            new Rule("DELETE", "/api/user/*", "sys:user:delete"),

            new Rule("POST", "/api/server", "asset:server:add"),
            new Rule("POST", "/api/server/*/clone", "asset:server:add"),
            new Rule("PUT", "/api/server/*", "asset:server:edit"),
            new Rule("DELETE", "/api/server/*", "asset:server:delete"),
            new Rule("POST", "/api/server/sync", "asset:server:sync"),
            new Rule("POST", "/api/server/import/**", "asset:server:import"),
            new Rule("POST", "/api/server/probe/all", "asset:server:probe"),
            new Rule("POST", "/api/server/*/probe", "asset:server:probe"),
            new Rule("POST", "/api/server/ssh/**", "asset:server:probe"),
            new Rule("POST", "/api/server/*/ssh/**", "asset:server:probe"),

            new Rule("POST", "/api/middleware", "asset:middleware:add"),
            new Rule("PUT", "/api/middleware/*", "asset:middleware:edit"),
            new Rule("DELETE", "/api/middleware/*", "asset:middleware:delete"),
            new Rule("POST", "/api/middleware/probe", "asset:middleware:probe"),
            new Rule("POST", "/api/middleware/*/probe", "asset:middleware:probe"),

            new Rule("POST", "/api/app", "asset:app:add"),
            new Rule("PUT", "/api/app/*", "asset:app:edit"),
            new Rule("DELETE", "/api/app/*", "asset:app:delete"),

            new Rule("POST", "/api/project", "asset:project:add"),
            new Rule("PUT", "/api/project/*/assets", "asset:project:assign"),
            new Rule("PUT", "/api/project/*/roles", "asset:project:assign"),
            new Rule("PUT", "/api/project/server/*", "asset:project:assign", "asset:server:edit"),
            new Rule("PUT", "/api/project/*", "asset:project:edit"),
            new Rule("DELETE", "/api/project/*", "asset:project:delete"),

            new Rule("POST", "/api/alert/rule", "alert:rule:add"),
            new Rule("PUT", "/api/alert/rule/*", "alert:rule:edit"),
            new Rule("DELETE", "/api/alert/rule/*", "alert:rule:delete"),

            new Rule("POST", "/api/exec/**", "exec:run"),

            new Rule("POST", "/api/db/execute", "db:execute"),
            new Rule("POST", "/api/db/connections/*/test", "db:test"),
            new Rule("POST", "/api/db/connections/sync", "db:test"),

            new Rule("POST", "/api/elk", "elk:connection:add"),
            new Rule("PUT", "/api/elk/*", "elk:connection:edit"),
            new Rule("DELETE", "/api/elk/*", "elk:connection:delete"),
            new Rule("POST", "/api/elk/*/test", "elk:connection:test"),

            new Rule("PUT", "/api/settings", "settings:save"),
            new Rule("POST", "/api/settings/test-connection", "settings:save"),
            new Rule("POST", "/api/system/config/ai", "settings:ai"),
            new Rule("POST", "/api/system/config/ai/test", "settings:ai"),
            new Rule("POST", "/api/system/config/metrics", "settings:source"),
            new Rule("POST", "/api/system/config/metrics/test", "settings:source"),
            new Rule("PUT", "/api/monitor/panel-config", "settings:source"),
            new Rule("POST", "/api/monitor/metric-test", "settings:source"),
            new Rule("POST", "/api/metrics/definitions", "settings:source"),
            new Rule("PUT", "/api/metrics/definitions/*", "settings:source"),
            new Rule("DELETE", "/api/metrics/definitions/*", "settings:source"),
            new Rule("POST", "/api/metrics/preview", "settings:source"),
            new Rule("POST", "/api/agents/install", "asset:server:edit"),
            new Rule("POST", "/api/agents/uninstall", "asset:server:edit"),
            new Rule("DELETE", "/api/agents/installed/*", "asset:server:edit"),
            new Rule("POST", "/api/prometheus/**", "settings:source"),
            new Rule("PUT", "/api/prometheus/**", "settings:source"),
            new Rule("DELETE", "/api/prometheus/**", "settings:source"),
            new Rule("POST", "/api/agents/packages", "asset:server:edit"),
            new Rule("DELETE", "/api/agents/packages/**", "asset:server:edit"),
            new Rule("POST", "/api/ftp/servers", "asset:ftp:edit"),
            new Rule("PUT", "/api/ftp/servers/*", "asset:ftp:edit"),
            new Rule("DELETE", "/api/ftp/servers/*", "asset:ftp:edit"),
            new Rule("POST", "/api/ftp/servers/*/test", "asset:ftp:test"),
            new Rule("POST", "/api/ftp/test", "asset:ftp:test"),
            new Rule("POST", "/api/ftp/servers/*/pull", "asset:ftp:transfer"),
            new Rule("POST", "/api/ftp/distribute", "asset:ftp:transfer"),
            new Rule("DELETE", "/api/ftp/staged", "asset:ftp:transfer"),
            new Rule("GET", "/api/ftp/staged/download", "asset:ftp:transfer"),

            new Rule("POST", "/api/kb", "kb:create"),
            new Rule("DELETE", "/api/kb/doc/*", "kb:delete"),
            new Rule("DELETE", "/api/kb/*", "kb:delete"),
            new Rule("POST", "/api/kb/*/upload", "kb:upload"),
            new Rule("POST", "/api/kb/*/upload-batch", "kb:upload"),
            new Rule("POST", "/api/kb/*/import-obsidian", "kb:upload"),
            new Rule("POST", "/api/kb/*/doc", "kb:upload"),
            new Rule("PUT", "/api/kb/doc/*", "kb:upload"),

            new Rule("POST", "/api/diagnosis/run", "diagnosis:run")
    );

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!(handler instanceof HandlerMethod)) return true;
        String method = request.getMethod();
        if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method) || "OPTIONS".equalsIgnoreCase(method)) {
            return true;
        }
        String path = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && path.startsWith(context)) path = path.substring(context.length());

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String username = auth == null ? null : auth.getName();
        if (username == null || "anonymousUser".equals(username)) return true;
        if (accessControl.isSuperAdmin(username)) return true;

        Rule hit = null;
        for (Rule r : RULES) {
            if (r.method.equalsIgnoreCase(method) && matcher.match(r.pattern, path)) { hit = r; break; }
        }
        if (hit == null) return true;
        for (String permission : hit.permissions) {
            if (accessControl.hasPermission(username, permission)) return true;
        }
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(
                Result.error(403, "权限不足：当前角色无权执行该操作（需要 " + String.join(" / ", hit.permissions) + "）")));
        return false;
    }
}