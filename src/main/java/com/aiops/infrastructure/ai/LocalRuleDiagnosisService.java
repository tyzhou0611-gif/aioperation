package com.aiops.infrastructure.ai;

import java.util.stream.Collectors;
import com.aiops.common.PageResult;
import com.aiops.entity.DiagnosisHistory;
import com.aiops.infrastructure.logs.LogProvider;
import com.aiops.infrastructure.metrics.MetricsProvider;
import com.aiops.repository.DiagnosisHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@ConditionalOnMissingBean(name = "llmDiagnosisService")
public class LocalRuleDiagnosisService {

    private static final Logger log = LoggerFactory.getLogger(LocalRuleDiagnosisService.class);
    private final MetricsProvider metricsProvider;
    private final LogProvider logProvider;
    private final DiagnosisHistoryRepository diagnosisRepository;

    public LocalRuleDiagnosisService(MetricsProvider metricsProvider,
                                     LogProvider logProvider,
                                     DiagnosisHistoryRepository diagnosisRepository) {
        this.metricsProvider = metricsProvider;
        this.logProvider = logProvider;
        this.diagnosisRepository = diagnosisRepository;
        log.warn("AI诊断源：本地规则引擎（未配置大模型API Key）。如需LLM诊断，请配置 app.ai.api-key");
    }

    @Transactional
    public DiagnosisResult diagnose(Long serverId, Long alertId, String serverIp) {
        double cpu = metricsProvider.getCpuUsage(serverId);
        double mem = metricsProvider.getMemoryUsage(serverId);
        double disk = metricsProvider.getDiskUsage(serverId);

        PageResult<Map<String, Object>> logs = logProvider.searchLogs(serverId, "ERROR", null, null, 1, 20);
        List<String> errorMessages = logs.getRecords().stream()
                .map(m -> (String) m.get("message")).collect(Collectors.toList());

        String result = analyze(cpu, mem, disk, errorMessages);
        String suggestion = getSuggestion(result);

        DiagnosisHistory dh = new DiagnosisHistory();
        dh.setAlertId(alertId); dh.setServerId(serverId); dh.setServerIp(serverIp);
        dh.setDiagnosisType("RULE");
        dh.setInputData(String.format("{\"cpu\":%.1f,\"mem\":%.1f,\"disk\":%.1f,\"errorLogs\":%d}", cpu, mem, disk, errorMessages.size()));
        dh.setDiagnosisResult(result); dh.setSuggestion(suggestion); dh.setConfidence(0.7);
        diagnosisRepository.save(dh);

        return new DiagnosisResult(result, suggestion, 0.7);
    }

    private String analyze(double cpu, double mem, double disk, List<String> errors) {
        List<String> findings = new ArrayList<>();
        if (cpu > 90) findings.add("CPU使用率过高(>90%)，可能原因：进程死循环、突发流量");
        if (mem > 90) findings.add("内存使用率过高(>90%)，可能原因：内存泄漏、OOM");
        if (disk > 90) findings.add("磁盘使用率过高(>90%)，可能原因：日志未轮转、数据暴涨");
        if (errors.stream().anyMatch(e -> e.contains("OutOfMemory") || e.contains("OOM")))
            findings.add("检测到OOM异常，建议排查JVM参数或增加内存");
        if (errors.stream().anyMatch(e -> e.contains("Connection refused") || e.contains("timeout")))
            findings.add("检测到连接超时异常，建议检查网络或服务状态");
        if (errors.stream().anyMatch(e -> e.contains("Disk Full") || e.contains("No space")))
            findings.add("检测到磁盘空间不足，建议清理日志或扩容");
        if (findings.isEmpty()) findings.add("当前指标在正常范围内，未发现明显异常。建议检查应用日志获取更多线索。");
        return String.join("；", findings);
    }

    private String getSuggestion(String result) {
        if (result.contains("CPU")) return "建议：1) 排查top/htop定位高CPU进程 2) 检查是否有死循环 3) 考虑水平伸缩";
        if (result.contains("内存") || result.contains("OOM")) return "建议：1) 使用jmap/jstat分析堆内存 2) 检查GC日志 3) 增大-Xmx参数";
        if (result.contains("磁盘")) return "建议：1) 清理过期日志(logrotate) 2) 扩容磁盘 3) 设置磁盘告警阈值";
        if (result.contains("连接超时")) return "建议：1) 检查目标服务端口 2) 查看网络连通性 3) 重启下游服务";
        return "建议：综合检查系统日志和应用日志，无明显指标异常";
    }
}