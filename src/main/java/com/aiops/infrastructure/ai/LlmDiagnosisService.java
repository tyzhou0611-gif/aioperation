package com.aiops.infrastructure.ai;

import com.aiops.common.PageResult;
import com.aiops.entity.AppAsset;
import com.aiops.entity.DiagnosisHistory;
import com.aiops.entity.MiddlewareAsset;
import com.aiops.entity.ServerAsset;
import com.aiops.infrastructure.logs.LogProvider;
import com.aiops.infrastructure.metrics.MetricsProvider;
import com.aiops.repository.AppAssetRepository;
import com.aiops.repository.DiagnosisHistoryRepository;
import com.aiops.repository.MiddlewareAssetRepository;
import com.aiops.repository.ServerAssetRepository;
import com.aiops.service.MiddlewareMonitorService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * LLM 智能诊断：支持服务器、中间件、应用和自定义问题。
 * 采集目标真实信息（主机指标/日志、中间件节点与协议状态、应用信息）构造提示词；
 * 大模型不可用或调用失败时回退本地规则引擎。
 */
@Service("llmDiagnosisService")
@Primary
public class LlmDiagnosisService implements DiagnosisService {

    private static final Logger log = LoggerFactory.getLogger(LlmDiagnosisService.class);
    private static final int MAX_ERROR_LOGS = 20;
    private static final int MAX_LOG_CHARS = 300;
    private static final int MAX_MONITOR_CHARS = 8000;

    private static final String SYSTEM_PROMPT =
            "你是一名资深 SRE / 中间件 / 应用运维专家，诊断对象可能是服务器、中间件（Kafka、ZooKeeper、Redis、数据库、Hadoop 等）、应用，或用户自定义的运维问题。要求：\n" +
            "1. 只输出一个 JSON 对象，不要 Markdown 代码块，不要任何额外解释；\n" +
            "2. JSON 字段：diagnosis（根因分析，必须引用给定的指标/日志/节点状态/应用信息作为依据；若一切正常也要说明判断依据）、" +
            "suggestion（可执行的排查与修复建议，用 1) 2) 3) 编号）、confidence（0 到 1 之间的小数）；\n" +
            "3. 只能基于提供的数据分析，禁止编造未给出的日志、指标、节点或主机信息；证据不足时降低 confidence 并说明还需补充哪些信息；\n" +
            "4. JSON 字符串值内部不要使用英文双引号（必要时用中文引号），避免 JSON 转义错误；\n" +
            "5. 对中间件要结合其架构（主从/集群/副本/Quorum/队列等）和节点连通性分析；对自定义问题可给出通用但可执行的排查思路。";

    private final AIClientFactory aiFactory;
    private final MetricsProvider metricsProvider;
    private final LogProvider logProvider;
    private final DiagnosisHistoryRepository diagnosisRepository;
    private final ServerAssetRepository serverRepository;
    private final MiddlewareAssetRepository middlewareRepository;
    private final AppAssetRepository appRepository;
    private final MiddlewareMonitorService monitorService;
    private final LocalRuleDiagnosisService fallback;
    private final ObjectMapper mapper = new ObjectMapper();

    public LlmDiagnosisService(AIClientFactory aiFactory,
                               MetricsProvider metricsProvider,
                               LogProvider logProvider,
                               DiagnosisHistoryRepository diagnosisRepository,
                               ServerAssetRepository serverRepository,
                               MiddlewareAssetRepository middlewareRepository,
                               AppAssetRepository appRepository,
                               MiddlewareMonitorService monitorService,
                               LocalRuleDiagnosisService fallback) {
        this.aiFactory = aiFactory;
        this.metricsProvider = metricsProvider;
        this.logProvider = logProvider;
        this.diagnosisRepository = diagnosisRepository;
        this.serverRepository = serverRepository;
        this.middlewareRepository = middlewareRepository;
        this.appRepository = appRepository;
        this.monitorService = monitorService;
        this.fallback = fallback;
    }

    @Override
    public String engine() {
        AiChatClient client = aiFactory.getActive();
        return client != null && client.enabled() ? "LLM" : "RULE";
    }

    @Override
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<String, Object>(aiFactory.info());
        boolean enabled = "LLM".equals(engine());
        m.put("engine", enabled ? "LLM" : "RULE");
        if (!enabled) m.put("model", "");
        m.put("fallback", "RULE");
        return m;
    }

    @Override
    public DiagnosisResult diagnose(DiagnosisTarget target, Long alertId) {
        AiChatClient client = aiFactory.getActive();
        if (client == null || !client.enabled()) {
            log.info("大模型诊断不可用（provider 未配置或凭据缺失），回退本地规则引擎");
            return fallback.diagnose(target, alertId);
        }

        long start = System.currentTimeMillis();
        TargetContext ctx = buildContext(target);
        double cpu = -1, mem = -1, disk = -1;
        List<String> errors = new ArrayList<String>();
        if (ctx.serverId != null) {
            final Long sid = ctx.serverId;
            cpu = metric("cpu", () -> metricsProvider.getCpuUsage(sid));
            mem = metric("mem", () -> metricsProvider.getMemoryUsage(sid));
            disk = metric("disk", () -> metricsProvider.getDiskUsage(sid));
            errors = collectErrorLogs(sid);
        }

        String userPrompt = buildPrompt(target, ctx, cpu, mem, disk, errors);
        List<Map<String, String>> history = new ArrayList<Map<String, String>>();
        Map<String, String> userMsg = new LinkedHashMap<String, String>();
        userMsg.put("role", "user");
        userMsg.put("content", userPrompt);
        history.add(userMsg);

        String raw = null;
        try {
            raw = client.chat(SYSTEM_PROMPT, history);
        } catch (Exception e) {
            log.warn("大模型诊断调用异常: {}", e.getMessage());
        }
        long elapsed = System.currentTimeMillis() - start;
        if (raw == null || raw.trim().isEmpty()) {
            log.warn("大模型诊断无有效返回，回退本地规则引擎（target={}:{}）", target.getType(), target.getId());
            return fallback.diagnose(target, alertId);
        }

        String model = String.valueOf(aiFactory.info().get("model"));
        DiagnosisResult result = parse(raw, model, elapsed);
        persist(target, ctx, alertId, cpu, mem, disk, errors.size(), result);
        log.info("大模型诊断完成 target={}:{} model={} 耗时={}ms 置信度={}",
                target.getType(), target.getId(), model, elapsed, result.getConfidence());
        return result;
    }

    /** 解析诊断对象并补充上下文（中间件会执行一次只读监控采集） */
    private TargetContext buildContext(DiagnosisTarget target) {
        TargetContext ctx = new TargetContext();
        ctx.type = target.getType();
        ctx.id = target.getId();
        ctx.name = target.getName();
        ctx.serverId = target.getServerId();
        ctx.ip = target.getServerIp();
        ctx.detail = "（未提供详细资产信息）";

        try {
            if (target.isServer()) {
                Long sid = target.getId() != null ? target.getId() : target.getServerId();
                ServerAsset s = sid == null ? null : serverRepository.findById(sid).orElse(null);
                if (s != null) {
                    ctx.id = s.getId();
                    ctx.serverId = s.getId();
                    ctx.name = s.getHostname() != null ? s.getHostname() : s.getIp();
                    ctx.ip = s.getIp();
                    ctx.detail = "服务器 " + ctx.name + "（" + s.getIp() + "），OS: " + nv(s.getOs())
                            + "，环境: " + nv(s.getEnvironment()) + "，状态: " + nv(s.getStatus());
                }
            } else if (target.isMiddleware()) {
                MiddlewareAsset m = target.getId() == null ? null : middlewareRepository.findById(target.getId()).orElse(null);
                if (m != null) {
                    ctx.id = m.getId();
                    ctx.name = m.getName();
                    ctx.ip = m.getIp();
                    ctx.serverId = m.getServerId();
                    ctx.detail = "中间件 " + m.getName() + "，类型: " + m.getType() + "，版本: " + nv(m.getVersion())
                            + "，地址: " + nv(m.getIp()) + ":" + (m.getPort() == null ? "-" : m.getPort())
                            + "，集群: " + nv(m.getClusterName()) + "，环境: " + nv(m.getEnv()) + "，状态: " + nv(m.getStatus());
                    try {
                        Map<String, Object> monitor = monitorService.monitor(m.getId());
                        String json = mapper.writeValueAsString(monitor);
                        if (json.length() > MAX_MONITOR_CHARS) json = json.substring(0, MAX_MONITOR_CHARS) + "…";
                        ctx.monitor = json;
                    } catch (Exception e) {
                        ctx.monitor = "（读取中间件实时状态失败: " + e.getMessage() + "）";
                    }
                }
            } else if (target.isApp()) {
                AppAsset a = target.getId() == null ? null : appRepository.findById(target.getId()).orElse(null);
                if (a != null) {
                    ctx.id = a.getId();
                    ctx.name = a.getName();
                    ctx.serverId = a.getServerId();
                    ctx.detail = "应用 " + a.getName() + "，编码: " + a.getCode() + "，类型: " + nv(a.getType())
                            + "，语言: " + nv(a.getLanguage()) + "，部署: " + nv(a.getDeployType())
                            + "，版本: " + nv(a.getVersion()) + "，环境: " + nv(a.getEnv()) + "，状态: " + nv(a.getStatus())
                            + "，负责人: " + nv(a.getOwner()) + "，依赖中间件ID: " + nv(a.getDependsMiddlewareIds());
                }
            } else if (target.isCustom()) {
                ctx.name = target.getName() == null || target.getName().trim().isEmpty()
                        ? "自定义运维问题" : target.getName();
                ctx.detail = "用户自定义问题，没有绑定具体资产。";
            }
        } catch (Exception e) {
            log.warn("构建诊断上下文失败: {}", e.getMessage());
        }

        // 中间件/应用若有宿主服务器，补充主机信息与指标
        if (ctx.serverId != null && !"SERVER".equalsIgnoreCase(ctx.type)) {
            ServerAsset s = serverRepository.findById(ctx.serverId).orElse(null);
            if (s != null) {
                if (ctx.ip == null || ctx.ip.trim().isEmpty()) ctx.ip = s.getIp();
                ctx.host = (s.getHostname() != null ? s.getHostname() : s.getIp()) + "（" + s.getIp() + "）";
            }
        }
        return ctx;
    }

    private String buildPrompt(DiagnosisTarget target, TargetContext ctx,
                               double cpu, double mem, double disk, List<String> errors) {
        StringBuilder sb = new StringBuilder();
        sb.append("【诊断对象】").append(target.getType()).append(" · ").append(nv(ctx.name)).append("\n");
        sb.append("【对象信息】").append(nv(ctx.detail)).append("\n");
        if (ctx.host != null) sb.append("【宿主服务器】").append(ctx.host).append("\n");
        if (ctx.serverId != null) {
            sb.append("【主机指标】CPU ").append(pct(cpu)).append("，内存 ").append(pct(mem))
              .append("，磁盘 ").append(pct(disk)).append("\n");
            sb.append("【主机近期 ERROR 日志】");
            if (errors.isEmpty()) sb.append("无\n");
            else {
                sb.append("共 ").append(errors.size()).append(" 条\n");
                for (int i = 0; i < errors.size(); i++) sb.append(i + 1).append(". ").append(errors.get(i)).append("\n");
            }
        }
        if (ctx.monitor != null) {
            sb.append("【中间件实时状态（只读采集）】\n").append(ctx.monitor).append("\n");
        }
        String extra = target.getExtra();
        if (extra != null && !extra.trim().isEmpty()) {
            sb.append("【用户补充说明】\n").append(extra.trim()).append("\n");
        }
        sb.append("\n请基于以上真实数据输出诊断 JSON。");
        return sb.toString();
    }

    private DiagnosisResult parse(String raw, String model, long elapsed) {
        String json = extractJson(raw);
        if (json != null) {
            try {
                JsonNode root = mapper.readTree(json);
                String diagnosis = text(root, "diagnosis");
                String suggestion = text(root, "suggestion");
                if (diagnosis != null && !diagnosis.isEmpty()) {
                    if (suggestion == null || suggestion.isEmpty()) {
                        suggestion = "请结合以上分析逐项验证；如需更细化的操作步骤，可在 AI 助手中继续追问。";
                    }
                    double conf = confidence(root);
                    return new DiagnosisResult(diagnosis, suggestion, conf, "LLM", model, elapsed);
                }
            } catch (Exception e) {
                log.warn("解析大模型诊断 JSON 失败: {}", e.getMessage());
            }
        }
        String text = raw.trim();
        if (text.length() > 4000) text = text.substring(0, 4000) + "…";
        return new DiagnosisResult(text,
                "模型未按结构化格式返回，已展示原始分析；建议在 AI 助手中继续追问以获取可执行步骤。",
                0.6, "LLM", model, elapsed);
    }

    private void persist(DiagnosisTarget target, TargetContext ctx, Long alertId,
                         double cpu, double mem, double disk, int errorCount, DiagnosisResult result) {
        try {
            Map<String, Object> input = new LinkedHashMap<String, Object>();
            input.put("engine", "LLM");
            input.put("model", result.getModel());
            input.put("targetType", target.getType());
            input.put("targetId", target.getId());
            input.put("targetName", ctx.name);
            input.put("serverId", ctx.serverId);
            input.put("serverIp", ctx.ip);
            input.put("cpu", cpu);
            input.put("mem", mem);
            input.put("disk", disk);
            input.put("errorLogs", errorCount);
            input.put("elapsedMs", result.getElapsedMs());

            DiagnosisHistory dh = new DiagnosisHistory();
            dh.setAlertId(alertId);
            dh.setServerId(ctx.serverId);
            dh.setServerIp(ctx.ip);
            dh.setTargetType(target.getType());
            dh.setTargetId(target.getId());
            dh.setTargetName(ctx.name);
            dh.setDiagnosisType("LLM");
            dh.setInputData(mapper.writeValueAsString(input));
            dh.setDiagnosisResult(result.getDiagnosis());
            dh.setSuggestion(result.getSuggestion());
            dh.setConfidence(result.getConfidence());
            diagnosisRepository.save(dh);
        } catch (Exception e) {
            log.warn("保存诊断历史失败: {}", e.getMessage());
        }
    }

    private double metric(String name, Callable<Double> supplier) {
        try {
            Double v = supplier.call();
            return v == null ? -1d : v;
        } catch (Exception e) {
            log.debug("采集 {} 指标失败: {}", name, e.getMessage());
            return -1d;
        }
    }

    private List<String> collectErrorLogs(Long serverId) {
        List<String> errors = new ArrayList<String>();
        try {
            PageResult<Map<String, Object>> page = logProvider.searchLogs(serverId, "ERROR", null, null, 1, MAX_ERROR_LOGS);
            if (page != null && page.getRecords() != null) {
                for (Map<String, Object> m : page.getRecords()) {
                    Object msg = m.get("message");
                    if (msg != null) errors.add(truncate(String.valueOf(msg), MAX_LOG_CHARS));
                }
            }
        } catch (Exception e) {
            log.debug("读取错误日志失败: {}", e.getMessage());
        }
        return errors;
    }

    private String pct(double v) {
        return v < 0 ? "未采集" : String.format("%.1f%%", v);
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        String oneLine = s.replace("\r", " ").replace("\n", " ");
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "…";
    }

    private double confidence(JsonNode root) {
        JsonNode n = root.path("confidence");
        double v = 0.6;
        if (n.isNumber()) v = n.asDouble();
        else if (n.isTextual()) {
            try {
                String t = n.asText().replace("%", "").trim();
                v = Double.parseDouble(t);
                if (v > 1) v = v / 100d;
            } catch (Exception ignore) { }
        }
        if (v < 0) v = 0;
        if (v > 1) v = 1;
        return v;
    }

    private String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isTextual() ? v.asText().trim() : "";
    }

    private String extractJson(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        return raw.substring(start, end + 1);
    }

    private String nv(String s) { return s == null || s.trim().isEmpty() ? "-" : s; }

    private static class TargetContext {
        String type;
        Long id;
        String name;
        Long serverId;
        String ip;
        String host;
        String detail;
        String monitor;
    }
}