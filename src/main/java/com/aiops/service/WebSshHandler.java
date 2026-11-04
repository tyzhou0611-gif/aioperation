package com.aiops.service;

import com.aiops.entity.ServerAsset;
import com.aiops.repository.ServerAssetRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.Session;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /ws/ssh：交互式 Web SSH 终端（类 Xshell）。
 *
 * 客户端消息（JSON）：
 *   {"type":"input","data":"ls\r"}
 *   {"type":"resize","cols":120,"rows":30}
 * 服务端消息（JSON）：
 *   {"type":"status","status":"connecting|ready|closed"}
 *   {"type":"output","data":"..."}
 *   {"type":"error","message":"..."}
 *
 * 注意：交互式终端会绕过命令安全校验，仅允许访问当前用户有权访问的服务器，并记录审计日志。
 */
@Slf4j
@Component
public class WebSshHandler extends TextWebSocketHandler {

    private final ServerAssetRepository serverRepository;
    private final ServerSshService serverSshService;
    private final AccessControlService accessControl;
    private final ExecHistoryService historyService;
    private final ThreadPoolTaskExecutor sshExecutor;
    private final ObjectMapper mapper = new ObjectMapper();

    private final Map<String, ShellState> states = new ConcurrentHashMap<String, ShellState>();

    public WebSshHandler(ServerAssetRepository serverRepository,
                         ServerSshService serverSshService,
                         AccessControlService accessControl,
                         ExecHistoryService historyService,
                         ThreadPoolTaskExecutor sshExecutor) {
        this.serverRepository = serverRepository;
        this.serverSshService = serverSshService;
        this.accessControl = accessControl;
        this.historyService = historyService;
        this.sshExecutor = sshExecutor;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession ws) {
        ShellState state = new ShellState();
        state.ws = ws;
        state.operator = String.valueOf(ws.getAttributes().getOrDefault("username", "anonymous"));
        state.startedAt = System.currentTimeMillis();
        states.put(ws.getId(), state);
        sshExecutor.execute(() -> openShell(state));
    }

    private void openShell(ShellState state) {
        WebSocketSession ws = state.ws;
        try {
            String sid = String.valueOf(ws.getAttributes().getOrDefault("serverId", ""));
            Long serverId;
            try { serverId = Long.valueOf(sid); } catch (Exception e) { serverId = null; }
            if (serverId == null) {
                sendError(ws, "缺少 serverId"); closeWs(ws); close(state); return;
            }
            ServerAsset server = serverRepository.findById(serverId).orElse(null);
            if (server == null) {
                sendError(ws, "服务器不存在: " + serverId); closeWs(ws); close(state); return;
            }
            Set<Long> allowed = accessControl.accessibleServerIds(state.operator);
            if (allowed != null && !allowed.contains(serverId)) {
                sendError(ws, "无权访问该服务器（不在当前角色的项目范围内）"); closeWs(ws); close(state); return;
            }
            state.server = server;
            sendStatus(ws, "connecting", "正在连接 " + server.getIp() + " …");
            Session session = serverSshService.openSessionForServer(serverId, 12000);
            ChannelShell channel = (ChannelShell) session.openChannel("shell");
            channel.setPtyType("xterm-256color");
            channel.setPtySize(120, 30, 0, 0);
            InputStream in = channel.getInputStream();
            OutputStream out = channel.getOutputStream();
            channel.connect(10000);
            state.session = session;
            state.channel = channel;
            state.input = out;
            Map<String, Object> ready = new LinkedHashMap<String, Object>();
            ready.put("type", "ready");
            ready.put("status", "ready");
            ready.put("server", serverSshService.connectionInfo(server));
            send(ws, ready);

            state.reader = new Thread(() -> pumpOutput(state, in), "web-ssh-" + ws.getId());
            state.reader.setDaemon(true);
            state.reader.start();
            audit(state, 200, "CONNECTED");
            log.info("Web SSH 已连接 server={} ip={} user={} operator={}",
                    server.getId(), server.getIp(), server.getSshUser(), state.operator);
        } catch (Exception e) {
            log.warn("Web SSH 连接失败 serverId={}: {}", ws.getAttributes().get("serverId"), e.getMessage());
            sendError(ws, "SSH 连接失败: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            closeWs(ws);
            close(state);
        }
    }

    private void closeWs(WebSocketSession ws) {
        try {
            if (ws.isOpen()) ws.close(CloseStatus.POLICY_VIOLATION);
        } catch (Exception ignore) { }
    }

    private void pumpOutput(ShellState state, InputStream in) {
        Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
        char[] buf = new char[4096];
        try {
            int n;
            while (!state.closed && (n = reader.read(buf)) >= 0) {
                if (n == 0) continue;
                Map<String, Object> msg = new LinkedHashMap<String, Object>();
                msg.put("type", "output");
                msg.put("data", new String(buf, 0, n));
                send(state.ws, msg);
            }
            if (!state.closed) sendStatus(state.ws, "closed", "SSH 会话已结束");
        } catch (Exception e) {
            if (!state.closed) sendStatus(state.ws, "closed", "SSH 连接断开: " + e.getMessage());
        } finally {
            close(state);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession ws, TextMessage message) {
        ShellState state = states.get(ws.getId());
        if (state == null) return;
        try {
            if (message.getPayloadLength() > 64 * 1024) {
                sendError(ws, "输入过长"); return;
            }
            Map<String, Object> msg = mapper.readValue(message.getPayload(), Map.class);
            String type = str(msg.get("type"));
            if ("input".equalsIgnoreCase(type)) {
                String data = str(msg.get("data"));
                if (data != null && state.input != null && !state.closed) {
                    synchronized (state) {
                        state.input.write(data.getBytes(StandardCharsets.UTF_8));
                        state.input.flush();
                    }
                }
            } else if ("resize".equalsIgnoreCase(type)) {
                int cols = Math.max(10, Math.min(500, intVal(msg.get("cols"), 120)));
                int rows = Math.max(5, Math.min(300, intVal(msg.get("rows"), 30)));
                if (state.channel != null && !state.closed) {
                    state.channel.setPtySize(cols, rows, 0, 0);
                }
            } else if ("ping".equalsIgnoreCase(type)) {
                Map<String, Object> pong = new LinkedHashMap<String, Object>();
                pong.put("type", "pong");
                send(ws, pong);
            }
        } catch (Exception e) {
            log.debug("Web SSH 消息处理失败: {}", e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession ws, CloseStatus status) {
        ShellState state = states.remove(ws.getId());
        if (state != null) close(state);
    }

    private void close(ShellState state) {
        if (state.closed) return;
        state.closed = true;
        try { if (state.channel != null && state.channel.isConnected()) state.channel.disconnect(); } catch (Exception ignore) {}
        try { if (state.session != null && state.session.isConnected()) state.session.disconnect(); } catch (Exception ignore) {}
        if (state.reader != null) state.reader.interrupt();
        states.remove(state.ws.getId());
        audit(state, 200, "CLOSED");
    }

    private void audit(ShellState state, int status, String action) {
        try {
            Map<String, Object> params = new LinkedHashMap<String, Object>();
            params.put("action", action);
            params.put("serverId", state.server == null ? null : state.server.getId());
            params.put("ip", state.server == null ? null : state.server.getIp());
            params.put("user", state.server == null ? null : state.server.getSshUser());
            long duration = System.currentTimeMillis() - state.startedAt;
            historyService.audit(state.operator, "/ws/ssh", "WS", mapper.writeValueAsString(params), status, duration);
        } catch (Exception ignore) { /* 审计失败不影响终端 */ }
    }

    private void sendStatus(WebSocketSession ws, String status, String message) {
        Map<String, Object> msg = new LinkedHashMap<String, Object>();
        msg.put("type", "status");
        msg.put("status", status);
        if (message != null) msg.put("message", message);
        send(ws, msg);
    }

    private void sendError(WebSocketSession ws, String message) {
        Map<String, Object> msg = new LinkedHashMap<String, Object>();
        msg.put("type", "error");
        msg.put("message", message);
        send(ws, msg);
    }

    private void send(WebSocketSession ws, Map<String, Object> payload) {
        synchronized (ws) {
            try {
                if (ws.isOpen()) ws.sendMessage(new TextMessage(mapper.writeValueAsString(payload)));
            } catch (Exception e) {
                log.debug("Web SSH 发送失败: {}", e.getMessage());
            }
        }
    }

    private String str(Object o) { return o == null ? null : o.toString(); }

    private int intVal(Object o, int def) {
        try { return o == null ? def : Integer.parseInt(o.toString()); } catch (Exception e) { return def; }
    }

    private static class ShellState {
        WebSocketSession ws;
        ServerAsset server;
        Session session;
        ChannelShell channel;
        OutputStream input;
        Thread reader;
        String operator;
        long startedAt;
        volatile boolean closed;
    }
}