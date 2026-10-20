package com.aiops.controller;

import com.aiops.common.Result;
import com.aiops.controller.vo.PlanResult;
import com.aiops.entity.AiChatMessage;
import com.aiops.entity.AiChatSession;
import com.aiops.service.AICopilotService;
import com.aiops.service.ExecService;
import com.aiops.service.QuickCommandService;
import com.aiops.service.SshResult;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 运维 Copilot 接口：自然语言 → 结构化方案 + 一键执行。
 * 无大模型时降级本地规则模板。
 */
@RestController
@RequestMapping("/api/copilot")
public class CopilotController {

    private final AICopilotService copilotService;
    private final QuickCommandService quickCommandService;
    private final ExecService execService;
    private final com.aiops.service.NavigationValidator navigationValidator;

    public CopilotController(AICopilotService copilotService, QuickCommandService quickCommandService,
                            ExecService execService, com.aiops.service.NavigationValidator navigationValidator) {
        this.copilotService = copilotService; this.quickCommandService = quickCommandService;
        this.execService = execService; this.navigationValidator = navigationValidator;
    }

    /** 跳转前二次校验（可选）：校验 target 合法 + 资产存在 */
    @PostMapping("/navigation/validate")
    public Result<com.aiops.controller.vo.NavigationHint> validateNav(
            @RequestBody com.aiops.controller.vo.NavigationHint nav) {
        return Result.success(navigationValidator.validate(nav));
    }

    @PostMapping("/chat")
    @SuppressWarnings("unchecked")
    public Result<PlanResult> chat(@RequestBody Map<String, Object> body, Authentication auth) {
        Long sessionId = body.get("sessionId") == null ? null : Long.valueOf(body.get("sessionId").toString());
        String message = str(body.get("message"));
        Map<String, Object> ctx = (Map<String, Object>) body.get("contextAsset");
        String ctxType = ctx == null ? null : str(ctx.get("type"));
        Long ctxId = ctx == null || ctx.get("id") == null ? null : Long.valueOf(ctx.get("id").toString());
        Long kbId = body.get("kbId") == null ? null : Long.valueOf(body.get("kbId").toString());
        String username = auth != null ? auth.getName() : "anonymous";
        PlanResult plan = copilotService.chat(sessionId, message, username, ctxType, ctxId, kbId);
        return Result.success(plan);
    }

    @GetMapping("/sessions")
    public Result<List<Map<String, Object>>> sessions(Authentication auth) {
        String username = auth != null ? auth.getName() : "anonymous";
        return Result.success(copilotService.listSessionSummaries(username));
    }

    /** 清空当前用户所有会话 */
    @DeleteMapping("/sessions")
    public Result<Map<String, Object>> clearSessions(Authentication auth) {
        String username = auth != null ? auth.getName() : "anonymous";
        int n = copilotService.deleteAllSessions(username);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("deleted", n);
        return Result.success("已清空 " + n + " 个会话", out);
    }

    @GetMapping("/sessions/{id}/messages")
    public Result<List<Map<String, Object>>> messages(@PathVariable Long id) {
        List<AiChatMessage> msgs = copilotService.listMessages(id);
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (AiChatMessage m : msgs) {
            Map<String, Object> mm = new LinkedHashMap<String, Object>();
            mm.put("id", m.getId());
            mm.put("role", m.getRole());
            mm.put("content", m.getContent());
            mm.put("createTime", m.getCreateTime());
            if (m.getMetadata() != null) {
                mm.put("plan", copilotService.parseMetadata(m.getMetadata()));
            }
            out.add(mm);
        }
        return Result.success(out);
    }

    @DeleteMapping("/sessions/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        copilotService.deleteSession(id);
        return Result.success("已删除", null);
    }

    /** 重命名会话 */
    @PatchMapping("/sessions/{id}")
    public Result<Void> rename(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        copilotService.renameSession(id, body.get("title") == null ? "" : body.get("title").toString());
        return Result.success("已重命名", null);
    }

    /** 将 AI 方案中的命令一键执行到指定服务器（同步，含黑名单校验） */
    @PostMapping("/execute")
    public Result<Map<String, Object>> execute(@RequestBody Map<String, Object> body, Authentication auth) {
        Long serverId = Long.valueOf(body.get("serverId").toString());
        String command = str(body.get("command"));
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        String bl = quickCommandService.checkBlacklist(command);
        if (bl != null) {
            data.put("success", false); data.put("output", bl);
            return Result.success(data);
        }
        List<SshResult> rs = execService.executeOnServers(listOf(serverId), command);
        SshResult r = rs.isEmpty() ? null : rs.get(0);
        data.put("success", r != null && r.isSuccess());
        StringBuilder sb = new StringBuilder();
        if (r != null) {
            if (r.getStdout() != null) sb.append(r.getStdout());
            if (r.getStderr() != null && !r.getStderr().trim().isEmpty()) {
                if (sb.length() > 0) sb.append("\n");
                sb.append("[stderr] ").append(r.getStderr());
            }
            if (r.getErrorMessage() != null) sb.append(r.getErrorMessage());
        }
        data.put("output", sb.length() == 0 ? "(无输出)" : sb.toString());
        return Result.success(data);
    }

    private String str(Object o) { return o == null ? null : o.toString(); }
    private List<Long> listOf(Long v) {
        List<Long> l = new ArrayList<Long>(); l.add(v); return l;
    }
}