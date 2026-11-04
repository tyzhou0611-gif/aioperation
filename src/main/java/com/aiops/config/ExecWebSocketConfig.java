package com.aiops.config;

import com.aiops.security.JwtUtil;
import com.aiops.service.ExecWebSocketHandler;
import com.aiops.service.WebSshHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * WebSocket 配置：/ws/exec?token=<JWT>
 */
@Configuration
@EnableWebSocket
public class ExecWebSocketConfig implements WebSocketConfigurer {

    private final ExecWebSocketHandler handler;
    private final WebSshHandler webSshHandler;
    private final JwtUtil jwtUtil;

    public ExecWebSocketConfig(ExecWebSocketHandler handler, WebSshHandler webSshHandler, JwtUtil jwtUtil) {
        this.handler = handler;
        this.webSshHandler = webSshHandler;
        this.jwtUtil = jwtUtil;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/exec")
                .addInterceptors(authInterceptor())
                .setAllowedOriginPatterns("*");
        registry.addHandler(webSshHandler, "/ws/ssh")
                .addInterceptors(authInterceptor())
                .setAllowedOriginPatterns("*");
    }

    private HandshakeInterceptor authInterceptor() {
        return new HandshakeInterceptor() {
            @Override
            public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                           WebSocketHandler wsHandler, Map<String, Object> attributes) {
                String token = null;
                String serverId = null;
                if (request instanceof ServletServerHttpRequest) {
                    HttpServletRequest req = ((ServletServerHttpRequest) request).getServletRequest();
                    token = req.getParameter("token");
                    serverId = req.getParameter("serverId");
                    if (token == null || token.trim().isEmpty()) {
                        String auth = req.getHeader("Authorization");
                        if (auth != null && auth.startsWith("Bearer ")) token = auth.substring(7);
                    }
                }
                if (token == null || !jwtUtil.validateToken(token)) {
                    response.setStatusCode(HttpStatus.UNAUTHORIZED);
                    return false;
                }
                attributes.put("username", jwtUtil.getUsernameFromToken(token));
                attributes.put("serverId", serverId);
                return true;
            }

            @Override
            public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                       WebSocketHandler wsHandler, Exception exception) {
            }
        };
    }
}