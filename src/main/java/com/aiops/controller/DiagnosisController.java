package com.aiops.controller;

import com.aiops.common.Result;
import com.aiops.entity.DiagnosisHistory;
import com.aiops.infrastructure.ai.DiagnosisResult;
import com.aiops.infrastructure.ai.DiagnosisService;
import com.aiops.infrastructure.ai.DiagnosisTarget;
import com.aiops.repository.DiagnosisHistoryRepository;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/diagnosis")
public class DiagnosisController {
    private final DiagnosisService diagnosisService;
    private final DiagnosisHistoryRepository diagnosisRepository;

    public DiagnosisController(DiagnosisService s, DiagnosisHistoryRepository r) {
        this.diagnosisService = s; this.diagnosisRepository = r;
    }

    @PostMapping("/run")
    public Result<Map<String, Object>> run(@RequestBody Map<String, Object> req) {
        Long alertId = lng(req.get("alertId"));
        String type = str(req.get("targetType"));
        Long targetId = lng(req.get("targetId"));
        if (type == null || type.trim().isEmpty()) {
            type = lng(req.get("serverId")) != null ? "SERVER" : "CUSTOM";
        }
        type = type.trim().toUpperCase();
        if (targetId == null && "SERVER".equals(type)) targetId = lng(req.get("serverId"));

        DiagnosisTarget target = new DiagnosisTarget();
        target.setType(type);
        target.setId(targetId);
        target.setName(str(req.get("targetName")));
        target.setServerId(lng(req.get("serverId")));
        target.setServerIp(str(req.get("serverIp")));
        target.setExtra(str(req.get("extraPrompt")));

        if (target.isCustom()) {
            if (target.getExtra() == null || target.getExtra().trim().isEmpty()) {
                return Result.error("自定义诊断请填写补充说明");
            }
            if (target.getName() == null || target.getName().trim().isEmpty()) {
                target.setName("自定义运维问题");
            }
        } else if (!"SERVER".equals(type) && !"MIDDLEWARE".equals(type) && !"APP".equals(type)) {
            return Result.error("不支持的诊断对象类型: " + type);
        } else if (target.getId() == null) {
            return Result.error("请选择诊断对象");
        }
        if (target.isServer() && target.getServerId() == null) target.setServerId(target.getId());

        DiagnosisResult result = diagnosisService.diagnose(target, alertId);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("diagnosis", result.getDiagnosis());
        map.put("suggestion", result.getSuggestion());
        map.put("confidence", result.getConfidence());
        map.put("diagnosisType", result.getDiagnosisType());
        map.put("model", result.getModel());
        map.put("elapsedMs", result.getElapsedMs());
        map.put("targetType", target.getType());
        map.put("targetName", target.getName());
        return Result.success(map);
    }

    /** 当前实际生效的诊断引擎（LLM 或本地规则），供页面展示 */
    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        return Result.success(diagnosisService.status());
    }

    @GetMapping("/history")
    public Result<List<DiagnosisHistory>> history() {
        return Result.success(diagnosisRepository.findAll(Sort.by(Sort.Direction.DESC, "id")));
    }

    private String str(Object o) { return o == null ? null : o.toString(); }

    private Long lng(Object o) {
        if (o == null || o.toString().trim().isEmpty()) return null;
        try { return Long.valueOf(o.toString().trim()); } catch (Exception e) { return null; }
    }
}