package com.aiops.infrastructure.ai;

import java.util.Map;

/**
 * 智能诊断统一入口：大模型诊断优先，不可用时回退本地规则引擎。
 */
public interface DiagnosisService {
    DiagnosisResult diagnose(DiagnosisTarget target, Long alertId);

    /** 当前实际生效的引擎：LLM / RULE */
    String engine();

    /** 引擎状态（供前端展示实际使用的诊断源） */
    Map<String, Object> status();
}