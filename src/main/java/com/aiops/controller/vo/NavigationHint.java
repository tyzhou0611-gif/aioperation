package com.aiops.controller.vo;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 方案附带的智能跳转建议。
 * target 必须是 NavigationTarget 枚举之一（后端已校验），params 为跳转参数。
 */
public class NavigationHint {
    private String target;
    private Map<String, Object> params = new LinkedHashMap<String, Object>();
    private String label;
    private String reason;
    private boolean hasWarning;       // 校验失败但保留（如资产ID不存在）
    private String warningMessage;    // 警告原因

    public NavigationHint() {}

    public String getTarget() { return target; } public void setTarget(String v) { target = v; }
    public Map<String, Object> getParams() { return params; } public void setParams(Map<String, Object> v) { params = v == null ? new LinkedHashMap<String, Object>() : v; }
    public String getLabel() { return label; } public void setLabel(String v) { label = v; }
    public String getReason() { return reason; } public void setReason(String v) { reason = v; }
    public boolean isHasWarning() { return hasWarning; } public void setHasWarning(boolean v) { hasWarning = v; }
    public String getWarningMessage() { return warningMessage; } public void setWarningMessage(String v) { warningMessage = v; }
}