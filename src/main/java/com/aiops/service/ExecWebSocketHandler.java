package com.aiops.service;

import com.aiops.entity.ServerAsset;
import com.aiops.repository.ServerAssetRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * /ws/exec：多机并发命令执行，实时推送 stdout/stderr，支持 kill 与 dry-run。
 *
 * 客户端消息：
 *   {"action":"start","requestId":"...","serverIds":[1,2],"command":"df -h",
 *    "dryRun":false,"confirmDangerous":false,"previewToken":""}
 *   {"action":"kill","requestId":"..."}
 */
@Slf4j
@Component
public class ExecWebSocketHandler extends TextWebSocketHandler {

    private final ServerAssetRepository serverRepository;
    private final CommandSafetyService safetyService;
    private final StreamingSshService streamingSshService;
    private final ExecHistoryService historyService;
    private final ThreadPoolTaskExecutor sshExecutor;
    private final AccessControlService accessControl;
    private final ObjectMapper mapper = new ObjectMapper();

    /** sessionId -> requestId -> run */
    private final Map<String, Map<String, RunState>> sessions = new ConcurrentHashMap<String, Map<String, RunState>>();

    public ExecWebSocketHandler(ServerAssetRepository serverRepository,
                                CommandSafetyService safetyService,
                                StreamingSshService streamingSshService,
                                ExecHistoryService historyService,
                                ThreadPoolTaskExecutor sshExecutor,
                                AccessControlService accessControl) {
        this.serverRepository = serverRepository;
        this.safetyService = safetyService;
        this.streamingSshService = streamingSshService;
        this.historyService = historyService;
        this.sshExecutor = sshExecutor;
        this.accessControl = accessControl;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.put(session.getId(), new ConcurrentHashMap<String, RunState>());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            Map<String, Object> msg = mapper.readValue(message.getPayload(), Map.class);
            String action = str(msg.get("action"));
            if ("start".equalsIgnoreCase(action)) start(session, msg);
            else if ("kill".equalsIgnoreCase(action)) kill(session, msg);
            else sendError(session, str(msg.get("requestId")), "BAD_REQUEST", "未知操作: " + action, null);
        } catch (Exception e) {
            log.warn("WS 消息处理失败: {}", e.getMessage());
            sendError(session, null, "BAD_REQUEST", "消息格式错误: " + e.getMessage(), null);
        }
    }

    private void start(WebSocketSession ws, Map<String, Object> msg) {
        String requestId = str(msg.get("requestId"));
        if (requestId == null || requestId.trim().isEmpty()) requestId = UUID.randomUUID().toString().replace("-", "");
        String command = str(msg.get("command"));
        boolean dryRun = bool(msg.get("dryRun"));
        boolean confirmDangerous = bool(msg.get("confirmDangerous"));
        String previewToken = str(msg.get("previewToken"));
        String operator = String.valueOf(ws.getAttributes().getOrDefault("username", "anonymous"));

        Map<String, RunState> runs = sessions.computeIfAbsent(ws.getId(), k -> new ConcurrentHashMap<String, RunState>());
        if (runs.containsKey(requestId)) {
            sendError(ws, requestId, "BAD_REQUEST", "该 requestId 正在执行", null);
            return;
        }
        if (command == null || command.trim().isEmpty()) {
            sendError(ws, requestId, "BAD_REQUEST", "命令不能为空", null); return;
        }
        if (command.length() > 5000) {
            sendError(ws, requestId, "BAD_REQUEST", "命令长度不能超过 5000 字符", null); return;
        }
        List<Long> ids = longList(msg.get("serverIds"));
        if (ids.isEmpty()) {
            sendError(ws, requestId, "BAD_REQUEST", "请选择至少一台目标主机", null); return;
        }
        if (ids.size() > 50) {
            sendError(ws, requestId, "BAD_REQUEST", "单次最多 50 台主机", null); return;
        }
        List<ServerAsset> servers = serverRepository.findAllById(new ArrayList<Long>(new LinkedHashSet<Long>(ids)));
        if (servers.isEmpty()) {
            sendError(ws, requestId, "NOT_FOUND", "目标主机不存在", null); return;
        }
        java.util.Set<Long> allowed = accessControl.accessibleServerIds(operator);
        if (allowed != null) {
            for (ServerAsset s : servers) {
                if (!allowed.contains(s.getId())) {
                    sendError(ws, requestId, "FORBIDDEN", "无权在服务器 " + s.getIp() + " 上执行命令（不在当前角色的项目范围内）", null);
                    return;
                }
            }
        }
        // 按用户选择顺序排序
        final Map<Long, Integer> order = new LinkedHashMap<Long, Integer>();
        for (int i = 0; i < ids.size(); i++) order.put(ids.get(i), i);
        servers.sort((a, b) -> Integer.compare(
                order.getOrDefault(a.getId(), Integer.MAX_VALUE),
                order.getOrDefault(b.getId(), Integer.MAX_VALUE)));

        CommandSafetyService.Risk risk = safetyService.evaluate(command, servers);
        if (risk.isBlocked()) {
            sendError(ws, requestId, "BLOCKED", risk.getMessage(), riskMap(risk)); return;
        }
        if (risk.isRequiresConfirm() && !confirmDangerous) {
            sendError(ws, requestId, "NEEDS_CONFIRM", risk.getMessage(), riskMap(risk)); return;
        }
        if (risk.isDryRunRequired() && !dryRun
                && !safetyService.validPreviewToken(previewToken, command, ids)) {
            sendError(ws, requestId, "NEEDS_DRY_RUN", "生产环境高危命令必须先 dry-run 预览，确认后再执行", riskMap(risk)); return;
        }

        RunState run = new RunState();
        run.requestId = requestId;
        run.operator = operator;
        run.command = command;
        run.dryRun = dryRun;
        run.riskLevel = risk.getRiskLevel();
        run.servers = servers;
        run.startedAt = System.currentTimeMillis();
        run.remaining = new AtomicInteger(servers.size());

        if (dryRun) {
            run.previewToken = safetyService.issuePreviewToken(command, ids);
            run.runId = historyService.start(requestId, operator, command, servers.size(), true, risk.getRiskLevel()).getId();
            sendRunStart(ws, run, risk, run.previewToken);
            for (ServerAsset s : servers) {
                sendHost(ws, "host_start", requestId, s);
                Map<String, Object> end = hostEndPayload(requestId, s, 0, true, 0, false, null);
                end.put("dryRun", true);
                send(ws, end);
                historyService.saveDryHost(run.runId, s);
            }
            long duration = System.currentTimeMillis() - run.startedAt;
            historyService.finish(run.runId, servers.size(), 0, duration, "DRY_RUN");
            audit(run, servers.size(), 0, duration, true);
            Map<String, Object> end = new LinkedHashMap<String, Object>();
            end.put("type", "run_end");
            end.put("requestId", requestId);
            end.put("runId", run.runId);
            end.put("successCount", servers.size());
            end.put("failCount", 0);
            end.put("durationMs", duration);
            end.put("dryRun", true);
            end.put("previewToken", run.previewToken);
            send(ws, end);
            return;
        }

        run.runId = historyService.start(requestId, operator, command, servers.size(), false, risk.getRiskLevel()).getId();
        runs.put(requestId, run);
        sendRunStart(ws, run, risk, null);

        for (ServerAsset server : servers) {
            StreamingSshService.StreamHandle handle = new StreamingSshService.StreamHandle();
            run.handles.put(server.getId(), handle);
            sshExecutor.execute(() -> streamHost(ws, run, server, handle));
        }
    }

    private void streamHost(WebSocketSession ws, RunState run, ServerAsset server,
                            StreamingSshService.StreamHandle handle) {
        sendHost(ws, "host_start", run.requestId, server);
        SshResult result = streamingSshService.stream(server, run.command, handle, (stream, data) -> {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("type", "host_output");
            out.put("requestId", run.requestId);
            out.put("serverId", server.getId());
            out.put("stream", stream);
            out.put("data", data);
            send(ws, out);
        });
        try {
            historyService.saveHost(run.runId, server, result);
        } catch (Exception e) {
            log.warn("保存执行历史失败: {}", e.getMessage());
        }
        Map<String, Object> end = hostEndPayload(run.requestId, server, result.getExitCode(),
                result.isSuccess(), result.getDurationMs(), result.isKilled(), result.getErrorMessage());
        send(ws, end);

        int remaining = run.remaining.decrementAndGet();
        if (result.isSuccess()) run.success.incrementAndGet(); else run.fail.incrementAndGet();
        if (remaining <= 0) finishRun(ws, run);
    }

    private void finishRun(WebSocketSession ws, RunState run) {
        long duration = System.currentTimeMillis() - run.startedAt;
        int success = run.success.get();
        int fail = run.fail.get();
        historyService.finish(run.runId, success, fail, duration, run.killed ? "KILLED" : (fail == 0 ? "SUCCESS" : "PARTIAL"));
        audit(run, success, fail, duration, false);
        Map<String, Object> end = new LinkedHashMap<String, Object>();
        end.put("type", "run_end");
        end.put("requestId", run.requestId);
        end.put("runId", run.runId);
        end.put("successCount", success);
        end.put("failCount", fail);
        end.put("durationMs", duration);
        end.put("killed", run.killed);
        send(ws, end);
        Map<String, RunState> runs = sessions.get(ws.getId());
        if (runs != null) runs.remove(run.requestId);
    }

    private void kill(WebSocketSession ws, Map<String, Object> msg) {
        String requestId = str(msg.get("requestId"));
        Map<String, RunState> runs = sessions.get(ws.getId());
        RunState run = runs == null ? null : runs.get(requestId);
        if (run == null) {
            sendError(ws, requestId, "NOT_FOUND", "任务不存在或已结束", null); return;
        }
        run.killed = true;
        for (StreamingSshService.StreamHandle h : run.handles.values()) h.kill();
        Map<String, Object> ack = new LinkedHashMap<String, Object>();
        ack.put("type", "kill_ack");
        ack.put("requestId", requestId);
        send(ws, ack);
    }

    private void sendRunStart(WebSocketSession ws, RunState run, CommandSafetyService.Risk risk, String previewToken) {
        Map<String, Object> msg = new LinkedHashMap<String, Object>();
        msg.put("type", "run_start");
        msg.put("requestId", run.requestId);
        msg.put("runId", run.runId);
        msg.put("dryRun", run.dryRun);
        msg.put("risk", riskMap(risk));
        if (previewToken != null) msg.put("previewToken", previewToken);
        List<Map<String, Object>> hosts = new ArrayList<Map<String, Object>>();
        for (ServerAsset s : run.servers) hosts.add(hostMap(s));
        msg.put("hosts", hosts);
        send(ws, msg);
    }

    private Map<String, Object> hostEndPayload(String requestId, ServerAsset server, int exitCode,
                                               boolean success, long durationMs, boolean killed, String error) {
        Map<String, Object> end = new LinkedHashMap<String, Object>();
        end.put("type", "host_end");
        end.put("requestId", requestId);
        end.put("serverId", server.getId());
        end.put("hostname", server.getHostname());
        end.put("ip", server.getIp());
        end.put("exitCode", exitCode);
        end.put("success", success);
        end.put("durationMs", durationMs);
        end.put("killed", killed);
        if (error != null) end.put("error", error);
        return end;
    }

    private void sendHost(WebSocketSession ws, String type, String requestId, ServerAsset server) {
        Map<String, Object> msg = hostMap(server);
        msg.put("type", type);
        msg.put("requestId", requestId);
        send(ws, msg);
    }

    private Map<String, Object> hostMap(ServerAsset s) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("serverId", s.getId());
        m.put("hostname", s.getHostname());
        m.put("ip", s.getIp());
        m.put("environment", s.getEnvironment());
        return m;
    }

    private Map<String, Object> riskMap(CommandSafetyService.Risk risk) {
        if (risk == null) return null;
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("level", risk.getLevel().name());
        m.put("riskLevel", risk.getRiskLevel());
        m.put("message", risk.getMessage());
        m.put("blocked", risk.isBlocked());
        m.put("requiresConfirm", risk.isRequiresConfirm());
        m.put("production", risk.isProduction());
        m.put("dryRunRequired", risk.isDryRunRequired());
        m.put("matchedRules", risk.getMatchedRules());
        return m;
    }

    private void sendError(WebSocketSession ws, String requestId, String code, String message, Object risk) {
        Map<String, Object> msg = new LinkedHashMap<String, Object>();
        msg.put("type", "error");
        msg.put("requestId", requestId);
        msg.put("code", code);
        msg.put("message", message);
        if (risk != null) msg.put("risk", risk);
        send(ws, msg);
    }

    private void audit(RunState run, int success, int fail, long duration, boolean dryRun) {
        try {
            Map<String, Object> params = new LinkedHashMap<String, Object>();
            params.put("requestId", run.requestId);
            params.put("runId", run.runId);
            params.put("command", run.command);
            params.put("hosts", run.servers.size());
            params.put("dryRun", dryRun);
            params.put("success", success);
            params.put("fail", fail);
            historyService.audit(run.operator, "/ws/exec", "WS", mapper.writeValueAsString(params), 200, duration);
        } catch (Exception ignore) {}
    }

    private void send(WebSocketSession ws, Map<String, Object> payload) {
        synchronized (ws) {
            try {
                if (ws.isOpen()) ws.sendMessage(new TextMessage(mapper.writeValueAsString(payload)));
            } catch (Exception e) {
                log.debug("WS 发送失败: {}", e.getMessage());
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Map<String, RunState> runs = sessions.remove(session.getId());
        if (runs != null) {
            for (RunState run : runs.values()) {
                run.killed = true;
                for (StreamingSshService.StreamHandle h : run.handles.values()) h.kill();
            }
        }
    }

    private String str(Object o) { return o == null ? null : o.toString(); }

    @SuppressWarnings("unchecked")
    private List<Long> longList(Object o) {
        List<Long> out = new ArrayList<Long>();
        if (o instanceof List) {
            for (Object v : (List<Object>) o) {
                if (v == null) continue;
                try { out.add(Long.valueOf(v.toString())); } catch (Exception ignore) {}
            }
        }
        return out;
    }

    private boolean bool(Object o) {
        return o != null && ("true".equalsIgnoreCase(o.toString()) || "1".equals(o.toString()));
    }

    private static class RunState {
        String requestId;
        String operator;
        String command;
        boolean dryRun;
        String riskLevel;
        String previewToken;
        long runId;
        long startedAt;
        List<ServerAsset> servers;
        Map<Long, StreamingSshService.StreamHandle> handles = new ConcurrentHashMap<Long, StreamingSshService.StreamHandle>();
        AtomicInteger remaining;
        AtomicInteger success = new AtomicInteger(0);
        AtomicInteger fail = new AtomicInteger(0);
        volatile boolean killed;
    }
}