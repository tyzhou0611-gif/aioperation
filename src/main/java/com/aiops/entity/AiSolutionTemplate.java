package com.aiops.entity;

import javax.persistence.*;

/** AI 本地规则模板（降级知识库） */
@Entity @Table(name = "ai_solution_template")
public class AiSolutionTemplate {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 500) private String keywords;
    @Column(nullable = false, length = 200) private String title;
    @Column(length = 500) private String summary;
    @Lob private String steps;
    @Lob @Column(name = "code_blocks") private String codeBlocks;
    @Column(name = "risk_level", length = 10) private String riskLevel;
    @Column(name = "risk_tip", length = 500) private String riskTip;
    @Lob private String navigation;

    public AiSolutionTemplate() {}
    public Long getId() { return id; } public void setId(Long v) { id = v; }
    public String getKeywords() { return keywords; } public void setKeywords(String v) { keywords = v; }
    public String getTitle() { return title; } public void setTitle(String v) { title = v; }
    public String getSummary() { return summary; } public void setSummary(String v) { summary = v; }
    public String getSteps() { return steps; } public void setSteps(String v) { steps = v; }
    public String getCodeBlocks() { return codeBlocks; } public void setCodeBlocks(String v) { codeBlocks = v; }
    public String getRiskLevel() { return riskLevel; } public void setRiskLevel(String v) { riskLevel = v; }
    public String getRiskTip() { return riskTip; } public void setRiskTip(String v) { riskTip = v; }
    public String getNavigation() { return navigation; } public void setNavigation(String v) { navigation = v; }
}