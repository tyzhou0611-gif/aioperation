package com.aiops.controller;

import com.aiops.common.Result;
import com.aiops.entity.ServerAsset;
import com.aiops.repository.ServerAssetRepository;
import com.aiops.service.CommandSafetyService;
import com.aiops.service.ExecBatchService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Size;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 批量命令执行 + 危险命令检查。
 */
@RestController
@RequestMapping("/api/exec")
public class ExecController {

    private final ExecBatchService batchService;
    private final CommandSafetyService safetyService;
    private final ServerAssetRepository serverRepository;

    public ExecController(ExecBatchService batchService, CommandSafetyService safetyService,
                          ServerAssetRepository serverRepository) {
        this.batchService = batchService;
        this.safetyService = safetyService;
        this.serverRepository = serverRepository;
    }

    @PostMapping("/batch")
    public Result<Map<String, Object>> batch(@Valid @RequestBody BatchExecRequest request, Authentication auth) {
        String operator = auth != null ? auth.getName() : "anonymous";
        Map<String, Object> data = batchService.execute(
                request.getServerIds(), request.getCommand(), operator,
                Boolean.TRUE.equals(request.getDryRun()),
                Boolean.TRUE.equals(request.getConfirmDangerous()),
                request.getPreviewToken());
        return Result.success(data);
    }

    /** 执行前安全检查：返回 riskLevel / blocked / requiresConfirm / dryRunRequired */
    @PostMapping("/check")
    public Result<Map<String, Object>> check(@RequestBody Map<String, Object> body) {
        String command = body.get("command") == null ? "" : body.get("command").toString();
        List<Long> ids = new ArrayList<Long>();
        Object raw = body.get("serverIds");
        if (raw instanceof List) {
            for (Object o : (List<?>) raw) {
                try { ids.add(Long.valueOf(o.toString())); } catch (Exception ignore) {}
            }
        }
        List<ServerAsset> servers = ids.isEmpty()
                ? new ArrayList<ServerAsset>() : serverRepository.findAllById(ids);
        CommandSafetyService.Risk risk = safetyService.evaluate(command, servers);
        return Result.success(riskMap(risk));
    }

    private Map<String, Object> riskMap(CommandSafetyService.Risk risk) {
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

    public static class BatchExecRequest {
        @NotEmpty(message = "服务器ID列表不能为空")
        @Size(min = 1, max = 50, message = "批量执行服务器数量范围: 1~50")
        private List<Long> serverIds;

        @NotBlank(message = "Shell命令不能为空")
        @Size(max = 5000, message = "命令长度不能超过5000字符")
        private String command;

        private Boolean dryRun;
        private Boolean confirmDangerous;
        private String previewToken;

        public List<Long> getServerIds() { return serverIds; }
        public void setServerIds(List<Long> v) { this.serverIds = v; }
        public String getCommand() { return command; }
        public void setCommand(String v) { this.command = v; }
        public Boolean getDryRun() { return dryRun; }
        public void setDryRun(Boolean v) { this.dryRun = v; }
        public Boolean getConfirmDangerous() { return confirmDangerous; }
        public void setConfirmDangerous(Boolean v) { this.confirmDangerous = v; }
        public String getPreviewToken() { return previewToken; }
        public void setPreviewToken(String v) { this.previewToken = v; }
    }
}