package com.aiops.service;

import com.aiops.controller.vo.AssetRef;
import com.aiops.controller.vo.CodeBlockItem;
import com.aiops.controller.vo.PlanResult;
import com.aiops.entity.AiChatMessage;
import com.aiops.entity.AiChatSession;
import com.aiops.entity.AppAsset;
import com.aiops.entity.MiddlewareAsset;
import com.aiops.entity.ServerAsset;
import com.aiops.infrastructure.ai.AIClientFactory;
import com.aiops.infrastructure.ai.AiChatClient;
import com.aiops.repository.AiChatMessageRepository;
import com.aiops.repository.AiChatSessionRepository;
import com.aiops.repository.AppAssetRepository;
import com.aiops.repository.MiddlewareAssetRepository;
import com.aiops.repository.ServerAssetRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI Copilot 编排：会话落库 + 大模型(有Key) / 本地规则(降级) 生成结构化方案。
 */
@Service
public class AICopilotService {

    private static final Logger log = LoggerFactory.getLogger(AICopilotService.class);

    private final AiChatSessionRepository sessionRepo;
    private final AiChatMessageRepository messageRepo;
    private final AISolutionTemplateService templateService;
    private final AIClientFactory aiFactory;
    private final ServerAssetRepository serverRepo;
    private final MiddlewareAssetRepository middlewareRepo;
    private final AppAssetRepository appRepo;
    private final NavigationValidator navigationValidator;
    private final KnowledgeService knowledgeService;
    private final ObjectMapper mapper = new ObjectMapper();

    public AICopilotService(AiChatSessionRepository sessionRepo, AiChatMessageRepository messageRepo,
                           AISolutionTemplateService templateService, AIClientFactory aiFactory,
                           ServerAssetRepository serverRepo, MiddlewareAssetRepository middlewareRepo,
                           AppAssetRepository appRepo, NavigationValidator navigationValidator,
                           KnowledgeService knowledgeService) {
        this.sessionRepo = sessionRepo; this.messageRepo = messageRepo;
        this.templateService = templateService; this.aiFactory = aiFactory;
        this.serverRepo = serverRepo; this.middlewareRepo = middlewareRepo; this.appRepo = appRepo;
        this.navigationValidator = navigationValidator;
        this.knowledgeService = knowledgeService;
    }

    @Transactional
    public PlanResult chat(Long sessionId, String message, String username,
                           String ctxType, Long ctxId, Long kbId) {
        // 会话
        AiChatSession session;
        if (sessionId != null && sessionRepo.existsById(sessionId)) {
            session = sessionRepo.getOne(sessionId);
        } else {
            session = new AiChatSession();
            session.setUsername(username);
            session.setTitle(message.length() > 20 ? message.substring(0, 20) : message);
            session = sessionRepo.save(session);
        }
        // 用户消息落库
        saveMessage(session.getId(), "user", message, null);

        String context = buildContext(ctxType, ctxId);
        String kbContext = buildKbContext(kbId, message);

        PlanResult plan = null;
        AiChatClient client = aiFactory.getActive();
        if (client != null) {
            plan = llmPlan(client, message, session.getId(), context, kbContext);
        }
        if (plan == null) {
            plan = templateService.match(message);
        }
        if (plan == null) {
            plan = guidePlan();
        }
        plan.setSessionId(session.getId());
        // 注入当前上下文资产，便于前端"执行到服务器"
        if (plan.getRelatedAssets().isEmpty() && ctxId != null) {
            List<AssetRef> refs = new ArrayList<AssetRef>();
            refs.add(new AssetRef(ctxType, resolveName(ctxType, ctxId), resolveServerId(ctxType, ctxId)));
            plan.setRelatedAssets(refs);
        }
        // 跳转建议：用上下文资产补全缺失的 id，再统一校验（非法 target 丢弃，ID 不存在则告警）
        enrichNavigation(plan, ctxType, ctxId);
        plan.setNavigation(navigationValidator.validate(plan.getNavigation()));
        enrichSuggestedServers(plan, ctxType, ctxId);
        // 助手消息落库
        try {
            saveMessage(session.getId(), "assistant", plan.getTitle() + "：" + plan.getSummary(),
                    mapper.writeValueAsString(plan));
        } catch (Exception e) { saveMessage(session.getId(), "assistant", plan.getTitle(), null); }
        return plan;
    }

    private PlanResult llmPlan(AiChatClient client, String message, Long sessionId, String context, String kbContext) {
        // 最近10条历史
        List<Map<String, String>> history = new ArrayList<Map<String, String>>();
        List<AiChatMessage> msgs = messageRepo.findBySessionIdOrderByIdAsc(sessionId);
        int from = Math.max(0, msgs.size() - 10);
        for (int i = from; i < msgs.size(); i++) {
            AiChatMessage m = msgs.get(i);
            Map<String, String> hm = new HashMap<String, String>();
            hm.put("role", m.getRole()); hm.put("content", m.getContent());
            history.add(hm);
        }
        String system = "你是一名资深 Linux/中间件运维专家。当前操作的资产上下文：\n" + context
                + (kbContext != null && !kbContext.isEmpty() ? "\n\n【知识库检索结果】\n" + kbContext : "")
                + "\n\n【平台真实资产清单（回答时优先引用，禁止编造 ID/IP）】\n" + buildAssetCatalog()
                + "\n请针对用户诉求给出可执行方案，严格只返回JSON，不要多余解释，结构：\n"
                + "{\"title\":\"标题\",\"summary\":\"概述\",\"steps\":[\"步骤1\"],"
                + "\"codeBlocks\":[{\"language\":\"bash\",\"label\":\"标签\",\"code\":\"命令\",\"suggestedServers\":[{\"id\":1,\"name\":\"server-01\",\"ip\":\"10.0.0.5\",\"reason\":\"该中间件宿主\"}]}],"
                + "\"riskLevel\":\"LOW|MEDIUM|HIGH\",\"riskTip\":\"风险提示\","
                + "\"relatedAssets\":[{\"type\":\"MIDDLEWARE|APP|SERVER\",\"name\":\"名称\",\"id\":123}],"
                + "\"navigation\":{\"target\":\"MIDDLEWARE_DETAIL\",\"params\":{\"id\":5,\"type\":\"KAFKA\"},"
                + "\"label\":\"查看 kafka-prod-01 实时状态\",\"reason\":\"需要查看该实例的实时指标\"}}\n"
                + "关于 navigation（智能跳转，最多1个）：\n"
                + "- 可用 target 枚举：SERVER_LIST / SERVER_DETAIL / MIDDLEWARE_LIST / MIDDLEWARE_DETAIL / "
                + "APP_LIST / APP_DETAIL / TOPOLOGY / ALERT_HISTORY / ALERT_RULE / LOG_MANAGEMENT / "
                + "EXEC_TASK / CRON_JOB / SYSTEM_CONFIG_AI / SYSTEM_CONFIG_MAIL / AUDIT_LOG\n"
                + "- 仅当问题明确指向某个资产/页面/操作时才返回 navigation；通用知识问题(如'什么是CAP定理')必须返回 \"navigation\":null。\n"
                + "- params 里的 id 必须是上面资产信息中真实存在的 ID，禁止编造。\n"
                + "- LOG_MANAGEMENT 可用 params:{serverId,keyword}；ALERT_HISTORY 可用 params:{serverId}；TOPOLOGY 可用 params:{focusId}。";
        String raw = client.chat(system, history);
        if (raw == null) return null;
        // 非流式大模型通常直接返回 JSON；HiAgent 等可能返回自然语言，则包装为单条方案
        String json = extractJson(raw);
        if (json == null) {
            PlanResult p = new PlanResult();
            p.setMode("LLM");
            p.setTitle("AI 助手回复");
            p.setSummary(raw);
            return p;
        }
        try {
            JsonNode root = mapper.readTree(json);
            PlanResult p = new PlanResult();
            p.setMode("LLM");
            p.setTitle(txt(root, "title", "运维方案"));
            p.setSummary(txt(root, "summary", ""));
            p.setRiskLevel(txt(root, "riskLevel", "LOW"));
            p.setRiskTip(txt(root, "riskTip", ""));
            JsonNode steps = root.path("steps");
            if (steps.isArray()) {
                List<String> sl = new ArrayList<String>();
                for (JsonNode s : steps) sl.add(s.asText());
                p.setSteps(sl);
            }
            JsonNode cb = root.path("codeBlocks");
            if (cb.isArray()) {
                List<CodeBlockItem> cl = new ArrayList<CodeBlockItem>();
                for (JsonNode c : cb) {
                    CodeBlockItem item = new CodeBlockItem(txt(c, "language", "bash"), txt(c, "label", "命令"), txt(c, "code", ""));
                    JsonNode ss = c.path("suggestedServers");
                    if (ss.isArray()) {
                        List<com.aiops.controller.vo.SuggestedServer> sl = new ArrayList<com.aiops.controller.vo.SuggestedServer>();
                        for (JsonNode s : ss) {
                            Long sid = s.path("id").isNumber() ? s.path("id").asLong() : null;
                            sl.add(new com.aiops.controller.vo.SuggestedServer(sid, txt(s, "name", ""), txt(s, "ip", ""), txt(s, "reason", "")));
                        }
                        item.setSuggestedServers(sl);
                    }
                    cl.add(item);
                }
                p.setCodeBlocks(cl);
            }
            JsonNode ra = root.path("relatedAssets");
            if (ra.isArray()) {
                List<AssetRef> al = new ArrayList<AssetRef>();
                for (JsonNode a : ra) {
                    Long id = a.path("id").isNumber() ? a.path("id").asLong() : null;
                    al.add(new AssetRef(txt(a, "type", "SERVER"), txt(a, "name", ""), id));
                }
                p.setRelatedAssets(al);
            }
            JsonNode nav = root.path("navigation");
            if (nav.isObject()) {
                try {
                    p.setNavigation(mapper.treeToValue(nav, com.aiops.controller.vo.NavigationHint.class));
                } catch (Exception e) { log.warn("解析navigation失败: {}", e.getMessage()); }
            }
            return p;
        } catch (Exception e) {
            log.warn("解析大模型返回JSON失败: {}", e.getMessage());
            return null;
        }
    }

    /** 用当前上下文资产补全 navigation 缺失的参数 */
    private void enrichNavigation(PlanResult plan, String ctxType, Long ctxId) {
        com.aiops.controller.vo.NavigationHint nav = plan.getNavigation();
        if (nav == null || ctxId == null || ctxType == null) return;
        String t = nav.getTarget() == null ? "" : nav.getTarget().toUpperCase();
        Map<String, Object> params = nav.getParams();
        if (t.endsWith("_DETAIL") && params.get("id") == null) {
            boolean match = (t.startsWith("SERVER") && "SERVER".equals(ctxType))
                    || (t.startsWith("MIDDLEWARE") && "MIDDLEWARE".equals(ctxType))
                    || (t.startsWith("APP") && "APP".equals(ctxType));
            if (match) params.put("id", ctxId);
        } else if ((t.equals("LOG_MANAGEMENT") || t.equals("ALERT_HISTORY") || t.equals("EXEC_TASK"))
                && params.get("serverId") == null) {
            Long sid = resolveServerId(ctxType, ctxId);
            if (sid != null) params.put("serverId", sid);
        }
    }

    private PlanResult guidePlan() {
        PlanResult p = new PlanResult();
        p.setMode("RULE");
        p.setTitle("未匹配到内置方案");
        p.setSummary("暂未匹配到内置运维方案。建议：1) 描述更具体的资产名或组件；2) 在【系统设置】配置大模型 API Key 以获得更智能的解答。");
        p.setRiskLevel("LOW");
        return p;
    }

    private void saveMessage(Long sid, String role, String content, String metadata) {
        AiChatMessage m = new AiChatMessage();
        m.setSessionId(sid); m.setRole(role); m.setContent(content); m.setMetadata(metadata);
        messageRepo.save(m);
    }

    public List<AiChatSession> listSessions(String username) {
        return sessionRepo.findByUsernameOrderByIdDesc(username);
    }

    public List<AiChatMessage> listMessages(Long sessionId) {
        return messageRepo.findBySessionIdOrderByIdAsc(sessionId);
    }

    @Transactional
    public void renameSession(Long sessionId, String title) {
        AiChatSession s = sessionRepo.findById(sessionId)
                .orElseThrow(() -> new com.aiops.common.ServiceException("会话不存在"));
        if (title != null && !title.trim().isEmpty()) {
            s.setTitle(title.trim().length() > 50 ? title.trim().substring(0, 50) : title.trim());
            sessionRepo.save(s);
        }
    }

    @Transactional
    public void deleteSession(Long sessionId) {
        messageRepo.deleteAll(messageRepo.findBySessionIdOrderByIdAsc(sessionId));
        sessionRepo.deleteById(sessionId);
    }

    /** 清空当前用户所有会话（先删消息再删会话） */
    @Transactional
    public int deleteAllSessions(String username) {
        List<AiChatSession> ss = sessionRepo.findByUsernameOrderByIdDesc(username);
        int n = 0;
        for (AiChatSession s : ss) {
            messageRepo.deleteAll(messageRepo.findBySessionIdOrderByIdAsc(s.getId()));
            n++;
        }
        sessionRepo.deleteAll(ss);
        return n;
    }

    /** 会话列表（含消息数） */
    public List<Map<String, Object>> listSessionSummaries(String username) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (AiChatSession s : sessionRepo.findByUsernameOrderByIdDesc(username)) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("id", s.getId());
            m.put("title", s.getTitle());
            m.put("updateTime", s.getUpdateTime());
            m.put("messageCount", messageRepo.findBySessionIdOrderByIdAsc(s.getId()).size());
            out.add(m);
        }
        return out;
    }

    /** 反序列化某条历史消息的结构化方案 */
    public PlanResult parseMetadata(String metadata) {
        if (metadata == null) return null;
        try {
            return mapper.readValue(metadata, new TypeReference<PlanResult>() {});
        } catch (Exception e) { return null; }
    }

    /** 构建平台资产清单（注入 System Prompt，控制长度：>50 台只列关键字段） */
    private String buildAssetCatalog() {
        StringBuilder sb = new StringBuilder();
        List<ServerAsset> servers = serverRepo.findAll();
        List<MiddlewareAsset> mws = middlewareRepo.findAll();
        List<AppAsset> apps = appRepo.findAll();
        boolean brief = servers.size() > 50;

        sb.append("【服务器】(共").append(servers.size()).append("台")
          .append(brief ? "，仅列前50" : "").append(")\n");
        int cnt = 0;
        for (ServerAsset s : servers) {
            if (brief && cnt++ >= 50) break;
            sb.append("- ID:").append(s.getId()).append(" host:").append(s.getHostname())
              .append(" IP:").append(s.getIp()).append(" 状态:").append(s.getStatus()).append("\n");
        }
        sb.append("【中间件】(共").append(mws.size()).append(")\n");
        for (MiddlewareAsset m : mws) {
            sb.append("- ID:").append(m.getId()).append(" name:").append(m.getName())
              .append(" type:").append(m.getType()).append(" 宿主serverId:").append(m.getServerId()).append("\n");
        }
        sb.append("【应用】(共").append(apps.size()).append(")\n");
        for (AppAsset a : apps) {
            sb.append("- ID:").append(a.getId()).append(" name:").append(a.getName())
              .append(" 宿主serverId:").append(a.getServerId())
              .append(" 依赖中间件IDs:").append(a.getDependsMiddlewareIds()).append("\n");
        }
        return sb.toString();
    }

    /** 为代码块补全建议执行服务器：LLM 未给时，用上下文资产的宿主服务器兜底 */
    private void enrichSuggestedServers(PlanResult plan, String ctxType, Long ctxId) {
        if (plan.getCodeBlocks() == null || plan.getCodeBlocks().isEmpty()) return;
        Long serverId = resolveServerId(ctxType, ctxId);
        if (serverId == null) return;
        ServerAsset srv = serverRepo.findById(serverId).orElse(null);
        if (srv == null) return;
        com.aiops.controller.vo.SuggestedServer sug = new com.aiops.controller.vo.SuggestedServer(
                srv.getId(), srv.getHostname() != null ? srv.getHostname() : srv.getIp(), srv.getIp(), "当前上下文资产的宿主服务器");
        for (CodeBlockItem cb : plan.getCodeBlocks()) {
            if (cb.getSuggestedServers() == null || cb.getSuggestedServers().isEmpty()) {
                List<com.aiops.controller.vo.SuggestedServer> list = new ArrayList<com.aiops.controller.vo.SuggestedServer>();
                list.add(sug);
                cb.setSuggestedServers(list);
            }
        }
    }

    /** 检索知识库 top-5 相关块，拼成 RAG 上下文 */
    private String buildKbContext(Long kbId, String query) {
        if (kbId == null) return "";
        try {
            List<com.aiops.entity.KbChunk> chunks = knowledgeService.retrieve(kbId, query, 5);
            if (chunks.isEmpty()) return "（未检索到相关知识库内容）";
            StringBuilder sb = new StringBuilder();
            int idx = 1;
            for (com.aiops.entity.KbChunk c : chunks) {
                sb.append("【资料").append(idx++).append("】来源：").append(c.getHeadingPath() != null ? c.getHeadingPath() : "未知")
                  .append("\n").append(c.getContent()).append("\n\n");
            }
            sb.append("请优先参考上述资料回答用户问题。若资料中没有相关内容，请基于通用知识回答并说明。");
            return sb.toString();
        } catch (Exception e) {
            log.warn("知识库检索失败: {}", e.getMessage());
            return "";
        }
    }

    private String buildContext(String type, Long id) {
        StringBuilder sb = new StringBuilder();
        if (id == null) { sb.append("（未指定具体资产）"); return sb.toString(); }
        if ("MIDDLEWARE".equals(type)) {
            MiddlewareAsset mw = middlewareRepo.findById(id).orElse(null);
            if (mw != null) sb.append("中间件: ").append(mw.getName()).append(" 类型=").append(mw.getType())
                    .append(" 版本=").append(nv(mw.getVersion())).append(" 地址=").append(nv(mw.getIp())).append(":").append(mw.getPort());
        } else if ("APP".equals(type)) {
            AppAsset a = appRepo.findById(id).orElse(null);
            if (a != null) sb.append("应用: ").append(a.getName()).append(" 类型=").append(nv(a.getType()))
                    .append(" 语言=").append(nv(a.getLanguage())).append(" 部署=").append(nv(a.getDeployType()));
        } else {
            ServerAsset s = serverRepo.findById(id).orElse(null);
            if (s != null) sb.append("服务器: ").append(nv(s.getHostname())).append(" IP=").append(s.getIp()).append(" OS=").append(nv(s.getOs()));
        }
        return sb.length() == 0 ? "（资产未找到）" : sb.toString();
    }

    private String resolveName(String type, Long id) {
        if (id == null) return "";
        try {
            if ("MIDDLEWARE".equals(type)) { MiddlewareAsset m = middlewareRepo.findById(id).orElse(null); return m == null ? "" : m.getName(); }
            if ("APP".equals(type)) { AppAsset a = appRepo.findById(id).orElse(null); return a == null ? "" : a.getName(); }
            ServerAsset s = serverRepo.findById(id).orElse(null); return s == null ? "" : (s.getHostname() != null ? s.getHostname() : s.getIp());
        } catch (Exception e) { return ""; }
    }

    /** 解析出该资产所在宿主服务器ID（用于一键执行） */
    private Long resolveServerId(String type, Long id) {
        if (id == null) return null;
        try {
            if ("MIDDLEWARE".equals(type)) { MiddlewareAsset m = middlewareRepo.findById(id).orElse(null); return m == null ? null : m.getServerId(); }
            if ("APP".equals(type)) { AppAsset a = appRepo.findById(id).orElse(null); return a == null ? null : a.getServerId(); }
            return id; // SERVER 本身就是
        } catch (Exception e) { return null; }
    }

    private String nv(String s) { return s == null ? "-" : s; }
    private String txt(JsonNode n, String k, String def) {
        JsonNode v = n.path(k);
        return v.isTextual() ? v.asText() : def;
    }

    /** 从可能带 ```json 包裹的文本中截取第一个完整JSON对象 */
    private String extractJson(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        return raw.substring(start, end + 1);
    }
}