package com.aiops.infrastructure.ai;

import java.util.LinkedHashMap;
import java.util.stream.Collectors;
import com.aiops.common.PageResult;
import com.aiops.entity.DiagnosisHistory;
import com.aiops.infrastructure.logs.LogProvider;
import com.aiops.infrastructure.metrics.MetricsProvider;
import com.aiops.repository.DiagnosisHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 本地规则诊断引擎：大模型不可用（未配置/调用失败）时的兜底。
 * 服务器可做指标/日志规则判断；中间件、应用、自定义问题仅给出可执行的兜底指引。
 */
@Service("localRuleDiagnosisService")
public class LocalRuleDiagnosisService implements DiagnosisService {

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
    }

    @Override
    public String engine() { return "RULE"; }

    @Override
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("engine", "RULE");
        m.put("provider", "none");
        m.put("model", "");
        m.put("reachable", false);
        return m;
    }

    @Override
    @Transactional
    public DiagnosisResult diagnose(DiagnosisTarget target, Long alertId) {
        if (target.isServer()) return diagnoseServer(target, alertId);

        String type = target.getType();
        String name = target.getName() == null || target.getName().trim().isEmpty() ? "-" : target.getName();
        String diagnosis;
        String suggestion;
        if (target.isMiddleware()) {
            diagnosis = "本地规则引擎：诊断对象为中间件「" + name + "」。规则引擎无法读取中间件的协议级指标、节点角色和日志，无法给出可靠的根因结论。";
            suggestion = "1) 在【系统设置 → AI 服务】配置大模型后重新诊断；"
                    + "2) 到【资产管理 → 中间件管理】打开该实例详情页，查看节点连通性、ZooKeeper/Redis/YARN 等实时状态；"
                    + "3) 如需即时排查，可使用【运维执行 → Web 终端】登录宿主服务器。";
        } else if (target.isApp()) {
            diagnosis = "本地规则引擎：诊断对象为应用「" + name + "」。规则引擎只能分析主机基础指标，无法结合应用日志/调用链给出根因。";
            suggestion = "1) 配置大模型后重新诊断，可结合宿主指标与日志分析；"
                    + "2) 查看应用宿主服务器状态与 ERROR 日志；"
                    + "3) 使用 Web 终端登录宿主服务器排查进程、端口与应用日志。";
        } else {
            diagnosis = "本地规则引擎无法分析自定义运维问题「" + name + "」。";
            suggestion = "请在【系统设置 → AI 服务】配置大模型 API Key 后重试；本地规则引擎仅支持基于服务器指标的阈值判断。";
        }

        DiagnosisResult result = new DiagnosisResult(diagnosis, suggestion, 0.3, "RULE", null, 0L);
        persist(target, null, target.getServerIp(), "RULE", result);
        log.debug("本地规则诊断完成（非服务器）target={}:{}", type, target.getId());
        return result;
    }

    private DiagnosisResult diagnoseServer(DiagnosisTarget target, Long alertId) {
        Long serverId = target.getServerId() != null ? target.getServerId() : target.getId();
        double cpu = metricsProvider.getCpuUsage(serverId);
        double mem = metricsProvider.getMemoryUsage(serverId);
        double disk = metricsProvider.getDiskUsage(serverId);

        PageResult<Map<String, Object>> logs = logProvider.searchLogs(serverId, "ERROR", null, null, 1, 20);
        List<String> errorMessages = logs.getRecords().stream()
                .map(m -> (String) m.get("message")).collect(Collectors.toList());

        String result = analyze(cpu, mem, disk, errorMessages);
        String suggestion = getSuggestion(result);
        DiagnosisResult dr = new DiagnosisResult(result, suggestion, 0.7, "RULE", null, 0L);

        try {
            Map<String, Object> input = new LinkedHashMap<String, Object>();
            input.put("engine", "RULE");
            input.put("targetType", "SERVER");
            input.put("targetId", target.getId());
            input.put("serverId", serverId);
            input.put("serverIp", target.getServerIp());
            input.put("cpu", cpu);
            input.put("mem", mem);
            input.put("disk", disk);
            input.put("errorLogs", errorMessages.size());
            DiagnosisHistory dh = newHistory(target, serverId, target.getServerIp(), "RULE", dr);
            dh.setInputData(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(input));
            diagnosisRepository.save(dh);
        } catch (Exception e) {
            log.warn("保存规则诊断历史失败: {}", e.getMessage());
        }

        log.debug("本地规则诊断完成 serverId={} cpu={} mem={} disk={}", serverId, cpu, mem, disk);
        return dr;
    }

    private void persist(DiagnosisTarget target, Long serverId, String serverIp, String engine, DiagnosisResult result) {
        try {
            DiagnosisHistory dh = newHistory(target, serverId, serverIp, engine, result);
            dh.setInputData("{\"engine\":\"" + engine + "\",\"targetType\":\"" + target.getType()
                    + "\",\"targetId\":" + (target.getId() == null ? "null" : target.getId()) + "}");
            diagnosisRepository.save(dh);
        } catch (Exception e) {
            log.warn("保存诊断历史失败: {}", e.getMessage());
        }
    }

    private DiagnosisHistory newHistory(DiagnosisTarget target, Long serverId, String serverIp,
                                        String engine, DiagnosisResult result) {
        DiagnosisHistory dh = new DiagnosisHistory();
        dh.setServerId(serverId);
        dh.setServerIp(serverIp);
        dh.setTargetType(target.getType());
        dh.setTargetId(target.getId());
        dh.setTargetName(target.getName());
        dh.setDiagnosisType(engine);
        dh.setDiagnosisResult(result.getDiagnosis());
        dh.setSuggestion(result.getSuggestion());
        dh.setConfidence(result.getConfidence());
        return dh;
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