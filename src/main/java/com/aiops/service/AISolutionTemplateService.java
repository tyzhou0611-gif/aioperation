package com.aiops.service;

import com.aiops.controller.vo.CodeBlockItem;
import com.aiops.controller.vo.PlanResult;
import com.aiops.entity.AiSolutionTemplate;
import com.aiops.repository.AiSolutionTemplateRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 本地规则模板匹配服务（无大模型时的降级方案）。
 * 对用户输入按关键词打分，命中最多的模板转成结构化方案。
 */
@Service
public class AISolutionTemplateService {

    private static final Logger log = LoggerFactory.getLogger(AISolutionTemplateService.class);
    private final AiSolutionTemplateRepository repository;
    private final ObjectMapper mapper = new ObjectMapper();

    public AISolutionTemplateService(AiSolutionTemplateRepository repository) {
        this.repository = repository;
    }

    /** 返回匹配到的方案；未命中返回 null */
    public PlanResult match(String message) {
        if (message == null || message.trim().isEmpty()) return null;
        String input = message.toLowerCase();
        List<AiSolutionTemplate> all = repository.findAllByOrderByIdAsc();
        AiSolutionTemplate best = null;
        int bestScore = 0;
        for (AiSolutionTemplate t : all) {
            int score = score(input, t.getKeywords());
            if (score > bestScore) { bestScore = score; best = t; }
        }
        if (best == null || bestScore == 0) return null;
        return toPlan(best);
    }

    private int score(String input, String keywordsCsv) {
        if (keywordsCsv == null) return 0;
        int s = 0;
        for (String kw : keywordsCsv.split(",")) {
            String k = kw.trim().toLowerCase();
            if (!k.isEmpty() && input.contains(k)) s++;
        }
        return s;
    }

    private PlanResult toPlan(AiSolutionTemplate t) {
        PlanResult p = new PlanResult();
        p.setMode("RULE");
        p.setTitle(t.getTitle());
        p.setSummary(t.getSummary());
        p.setRiskLevel(t.getRiskLevel());
        p.setRiskTip(t.getRiskTip());
        try {
            List<String> steps = mapper.readValue(t.getSteps(), new TypeReference<List<String>>() {});
            p.setSteps(steps);
        } catch (Exception e) { log.warn("解析steps失败: {}", e.getMessage()); }
        try {
            List<CodeBlockItem> blocks = mapper.readValue(t.getCodeBlocks(), new TypeReference<List<CodeBlockItem>>() {});
            p.setCodeBlocks(blocks);
        } catch (Exception e) { log.warn("解析codeBlocks失败: {}", e.getMessage()); }
        if (t.getNavigation() != null && !t.getNavigation().trim().isEmpty()) {
            try {
                com.aiops.controller.vo.NavigationHint nav =
                        mapper.readValue(t.getNavigation(), com.aiops.controller.vo.NavigationHint.class);
                p.setNavigation(nav);
            } catch (Exception e) { log.warn("解析navigation失败: {}", e.getMessage()); }
        }
        return p;
    }
}