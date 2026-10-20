package com.aiops.controller;

import com.aiops.common.Result;
import com.aiops.entity.DiagnosisHistory;
import com.aiops.infrastructure.ai.LocalRuleDiagnosisService;
import com.aiops.repository.DiagnosisHistoryRepository;
import org.springframework.web.bind.annotation.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/diagnosis")
public class DiagnosisController {
    private final LocalRuleDiagnosisService diagnosisService;
    private final DiagnosisHistoryRepository diagnosisRepository;

    public DiagnosisController(LocalRuleDiagnosisService s, DiagnosisHistoryRepository r) {
        this.diagnosisService = s; this.diagnosisRepository = r;
    }

    @PostMapping("/run")
    public Result<Map<String, Object>> run(@RequestBody Map<String, Object> req) {
        Long serverId = Long.valueOf(req.get("serverId").toString());
        Long alertId = req.get("alertId") != null ? Long.valueOf(req.get("alertId").toString()) : null;
        String ip = (String) req.getOrDefault("serverIp", "");
        com.aiops.infrastructure.ai.DiagnosisResult result = diagnosisService.diagnose(serverId, alertId, ip);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("diagnosis", result.getDiagnosis());
        map.put("suggestion", result.getSuggestion());
        map.put("confidence", result.getConfidence());
        return Result.success(map);
    }

    @GetMapping("/history")
    public Result<List<DiagnosisHistory>> history() {
        return Result.success(diagnosisRepository.findAll());
    }
}