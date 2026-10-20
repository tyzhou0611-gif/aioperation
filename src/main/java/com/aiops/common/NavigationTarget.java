package com.aiops.common;

/**
 * AI 助手智能跳转目标字典（后端校验用）。
 * 前端 utils/routerHelper.ts 维护同名的路由映射。
 */
public enum NavigationTarget {

    SERVER_LIST, SERVER_DETAIL,
    MIDDLEWARE_LIST, MIDDLEWARE_DETAIL,
    APP_LIST, APP_DETAIL,
    TOPOLOGY,
    ALERT_HISTORY, ALERT_RULE,
    LOG_MANAGEMENT,
    EXEC_TASK, CRON_JOB,
    SYSTEM_CONFIG_AI, SYSTEM_CONFIG_MAIL,
    AUDIT_LOG;

    /** 校验字符串是否为合法目标；非法返回 null */
    public static NavigationTarget fromString(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        try {
            return valueOf(s.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}