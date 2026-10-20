package com.aiops.controller.vo;

import java.util.ArrayList;
import java.util.List;

/** AI Copilot 结构化方案 */
public class PlanResult {
    private Long sessionId;
    private String title;
    private String summary;
    private List<String> steps = new ArrayList<String>();
    private List<CodeBlockItem> codeBlocks = new ArrayList<CodeBlockItem>();
    private String riskLevel = "LOW";
    private String riskTip;
    private List<AssetRef> relatedAssets = new ArrayList<AssetRef>();
    private NavigationHint navigation;   // 智能跳转建议（可为 null）
    private String mode; // LLM / RULE

    public PlanResult() {}
    public Long getSessionId() { return sessionId; } public void setSessionId(Long v) { sessionId = v; }
    public String getTitle() { return title; } public void setTitle(String v) { title = v; }
    public String getSummary() { return summary; } public void setSummary(String v) { summary = v; }
    public List<String> getSteps() { return steps; } public void setSteps(List<String> v) { steps = v; }
    public List<CodeBlockItem> getCodeBlocks() { return codeBlocks; } public void setCodeBlocks(List<CodeBlockItem> v) { codeBlocks = v; }
    public String getRiskLevel() { return riskLevel; } public void setRiskLevel(String v) { riskLevel = v; }
    public String getRiskTip() { return riskTip; } public void setRiskTip(String v) { riskTip = v; }
    public List<AssetRef> getRelatedAssets() { return relatedAssets; } public void setRelatedAssets(List<AssetRef> v) { relatedAssets = v; }
    public NavigationHint getNavigation() { return navigation; } public void setNavigation(NavigationHint v) { navigation = v; }
    public String getMode() { return mode; } public void setMode(String v) { mode = v; }
}