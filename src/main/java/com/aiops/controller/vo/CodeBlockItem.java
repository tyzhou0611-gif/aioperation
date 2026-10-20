package com.aiops.controller.vo;

import java.util.ArrayList;
import java.util.List;

/** AI 方案中的代码块 */
public class CodeBlockItem {
    private String language;
    private String label;
    private String code;
    private List<SuggestedServer> suggestedServers = new ArrayList<SuggestedServer>();

    public CodeBlockItem() {}
    public CodeBlockItem(String language, String label, String code) {
        this.language = language; this.label = label; this.code = code;
    }
    public String getLanguage() { return language; } public void setLanguage(String v) { language = v; }
    public String getLabel() { return label; } public void setLabel(String v) { label = v; }
    public String getCode() { return code; } public void setCode(String v) { code = v; }
    public List<SuggestedServer> getSuggestedServers() { return suggestedServers; }
    public void setSuggestedServers(List<SuggestedServer> v) { suggestedServers = v == null ? new ArrayList<SuggestedServer>() : v; }
}