package com.aiops.service;

import com.aiops.common.NavigationTarget;
import com.aiops.controller.vo.NavigationHint;
import com.aiops.entity.MiddlewareAsset;
import com.aiops.repository.AppAssetRepository;
import com.aiops.repository.MiddlewareAssetRepository;
import com.aiops.repository.ServerAssetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * 跳转建议校验器：
 * - target 不在字典中 → 丢弃（前端不渲染按钮，避免 404）
 * - params 中的资产 ID 不存在 → 保留但 hasWarning=true（防大模型幻觉）
 * - label 为空 → 用目标默认文案
 */
@Service
public class NavigationValidator {

    private static final Logger log = LoggerFactory.getLogger(NavigationValidator.class);

    private final ServerAssetRepository serverRepo;
    private final MiddlewareAssetRepository mwRepo;
    private final AppAssetRepository appRepo;

    public NavigationValidator(ServerAssetRepository serverRepo, MiddlewareAssetRepository mwRepo,
                              AppAssetRepository appRepo) {
        this.serverRepo = serverRepo; this.mwRepo = mwRepo; this.appRepo = appRepo;
    }

    /** 校验并清洗；非法 target 返回 null */
    public NavigationHint validate(NavigationHint nav) {
        if (nav == null) return null;
        NavigationTarget t = NavigationTarget.fromString(nav.getTarget());
        if (t == null) {
            log.warn("navigation.target 非法，丢弃: {}", nav.getTarget());
            return null;
        }
        nav.setTarget(t.name());

        if (nav.getLabel() == null || nav.getLabel().trim().isEmpty()) {
            nav.setLabel(defaultLabel(t));
        }

        Long id = asLong(nav.getParams().get("id"));
        Long serverId = asLong(nav.getParams().get("serverId"));
        Long focusId = asLong(nav.getParams().get("focusId"));

        switch (t) {
            case SERVER_DETAIL:
                if (id == null || !serverRepo.existsById(id)) warn(nav, "服务器 id=" + id + " 不存在，跳转后可能为空");
                break;
            case MIDDLEWARE_DETAIL:
                if (id == null || !mwRepo.existsById(id)) {
                    warn(nav, "中间件 id=" + id + " 不存在，跳转后可能为空");
                } else {
                    // 自动补全 type
                    if (nav.getParams().get("type") == null) {
                        MiddlewareAsset mw = mwRepo.findById(id).orElse(null);
                        if (mw != null) nav.getParams().put("type", mw.getType());
                    }
                }
                break;
            case APP_DETAIL:
                if (id == null || !appRepo.existsById(id)) warn(nav, "应用 id=" + id + " 不存在，跳转后可能为空");
                break;
            case LOG_MANAGEMENT:
            case ALERT_HISTORY:
            case EXEC_TASK:
                if (serverId != null && !serverRepo.existsById(serverId)) warn(nav, "服务器 id=" + serverId + " 不存在");
                break;
            case TOPOLOGY:
                if (focusId != null && !serverRepo.existsById(focusId) && !mwRepo.existsById(focusId)
                        && !appRepo.existsById(focusId)) {
                    warn(nav, "focusId=" + focusId + " 对应节点不存在");
                }
                break;
            default:
                break;
        }
        return nav;
    }

    private void warn(NavigationHint nav, String msg) {
        nav.setHasWarning(true);
        nav.setWarningMessage(msg);
        log.warn("navigation 校验警告: {}", msg);
    }

    private Long asLong(Object o) {
        if (o == null) return null;
        try { return Long.valueOf(o.toString()); } catch (Exception e) { return null; }
    }

    private String defaultLabel(NavigationTarget t) {
        switch (t) {
            case SERVER_LIST: return "前往服务器管理";
            case SERVER_DETAIL: return "查看服务器详情";
            case MIDDLEWARE_LIST: return "前往中间件管理";
            case MIDDLEWARE_DETAIL: return "查看中间件详情";
            case APP_LIST: return "前往应用管理";
            case APP_DETAIL: return "查看应用详情";
            case TOPOLOGY: return "打开拓扑大屏";
            case ALERT_HISTORY: return "查看告警历史";
            case ALERT_RULE: return "前往告警规则";
            case LOG_MANAGEMENT: return "查看相关日志";
            case EXEC_TASK: return "前往命令执行";
            case CRON_JOB: return "前往定时任务";
            case SYSTEM_CONFIG_AI: return "打开 AI 配置";
            case SYSTEM_CONFIG_MAIL: return "打开通知配置";
            case AUDIT_LOG: return "查看审计日志";
            default: return "前往相关页面";
        }
    }

    /** 供 /api/copilot/navigation/validate 使用 */
    public String describeTarget(String target) {
        NavigationTarget t = NavigationTarget.fromString(target);
        return t == null ? null : t.name().toLowerCase(Locale.ROOT);
    }
}