package com.aiops.service;

import com.aiops.entity.ExecRun;
import com.aiops.entity.ServerAsset;
import com.aiops.repository.ServerAssetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * REST 批量执行（WebSocket 不可用时的降级路径），带安全护栏和执行历史。
 */
@Service
@RequiredArgsConstructor
public class ExecBatchService {

    private final ServerAssetRepository serverRepository;
    private final ExecService execService;
    private final CommandSafetyService safetyService;
    private final ExecHistoryService historyService;
    private final AccessControlService accessControl;

    public Map<String, Object> execute(List<Long> serverIds, String command, String operator,
                                       boolean dryRun, boolean confirmDangerous, String previewToken) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        if (serverIds == null || serverIds.isEmpty()) return reject(out, "BAD_REQUEST", "请选择至少一台目标主机", null);
        if (command == null || command.trim().isEmpty()) return reject(out, "BAD_REQUEST", "命令不能为空", null);

        List<Long> ids = new ArrayList<Long>(new LinkedHashSet<Long>(serverIds));
        java.util.Set<Long> allowed = accessControl.accessibleServerIds(operator);
        if (allowed != null) {
            for (Long id : ids) {
                if (!allowed.contains(id)) {
                    return reject(out, "FORBIDDEN", "无权在服务器 id=" + id + " 上执行命令（不在当前角色的项目范围内）", null);
                }
            }
        }
        List<ServerAsset> servers = serverRepository.findAllById(ids);
        if (servers.isEmpty()) return reject(out, "NOT_FOUND", "目标主机不存在", null);

        CommandSafetyService.Risk risk = safetyService.evaluate(command, servers);
        if (risk.isBlocked()) return reject(out, "BLOCKED", risk.getMessage(), risk);
        if (risk.isRequiresConfirm() && !confirmDangerous) {
            return reject(out, "NEEDS_CONFIRM", risk.getMessage(), risk);
        }
        if (risk.isDryRunRequired() && !dryRun
                && !safetyService.validPreviewToken(previewToken, command, ids)) {
            return reject(out, "NEEDS_DRY_RUN", "生产环境高危命令必须先 dry-run 预览，确认后再执行", risk);
        }

        long startedAt = System.currentTimeMillis();
        Map<Long, ServerAsset> serverMap = new LinkedHashMap<Long, ServerAsset>();
        for (ServerAsset s : servers) serverMap.put(s.getId(), s);

        if (dryRun) {
            String token = safetyService.issuePreviewToken(command, ids);
            ExecRun run = historyService.start(null, operator, command, servers.size(), true, risk.getRiskLevel());
            List<SshResult> results = new ArrayList<SshResult>();
            for (ServerAsset s : servers) {
                SshResult r = new SshResult();
                r.setServerId(s.getId());
                r.setHostname(s.getHostname());
                r.setIp(s.getIp());
                r.setEnvironment(s.getEnvironment());
                r.setSuccess(true);
                r.setExitCode(0);
                r.setStdout("[DRY-RUN] 未实际执行，仅预览命令");
                r.setDurationMs(0);
                results.add(r);
                historyService.saveDryHost(run.getId(), s);
            }
            long duration = System.currentTimeMillis() - startedAt;
            historyService.finish(run.getId(), servers.size(), 0, duration, "DRY_RUN");
            audit(operator, run.getId(), command, servers.size(), 0, duration, true);
            out.put("rejected", false);
            out.put("dryRun", true);
            out.put("runId", run.getId());
            out.put("previewToken", token);
            out.put("risk", riskMap(risk));
            out.put("results", results);
            out.put("successCount", servers.size());
            out.put("failCount", 0);
            return out;
        }

        ExecRun run = historyService.start(null, operator, command, servers.size(), false, risk.getRiskLevel());
        List<SshResult> results = execService.executeOnServers(ids, command);
        int success = 0, fail = 0;
        for (SshResult r : results) {
            historyService.saveHost(run.getId(), serverMap.get(r.getServerId()), r);
            if (r.isSuccess()) success++; else fail++;
        }
        long duration = System.currentTimeMillis() - startedAt;
        historyService.finish(run.getId(), success, fail, duration, fail == 0 ? "SUCCESS" : "PARTIAL");
        audit(operator, run.getId(), command, servers.size(), fail, duration, false);
        out.put("rejected", false);
        out.put("dryRun", false);
        out.put("runId", run.getId());
        out.put("risk", riskMap(risk));
        out.put("results", results);
        out.put("successCount", success);
        out.put("failCount", fail);
        return out;
    }

    private void audit(String operator, Long runId, String command, int hosts, int fail, long duration, boolean dryRun) {
        try {
            Map<String, Object> params = new LinkedHashMap<String, Object>();
            params.put("runId", runId);
            params.put("command", command);
            params.put("hosts", hosts);
            params.put("fail", fail);
            params.put("dryRun", dryRun);
            historyService.audit(operator, "/api/exec/batch", "POST",
                    new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(params), 200, duration);
        } catch (Exception ignore) {}
    }

    private Map<String, Object> reject(Map<String, Object> out, String code, String message, CommandSafetyService.Risk risk) {
        out.put("rejected", true);
        out.put("code", code);
        out.put("message", message);
        if (risk != null) out.put("risk", riskMap(risk));
        return out;
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
}