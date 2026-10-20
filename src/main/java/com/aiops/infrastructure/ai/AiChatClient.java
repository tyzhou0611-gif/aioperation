package com.aiops.infrastructure.ai;

import java.util.List;
import java.util.Map;

/**
 * 大模型对话客户端统一接口。
 * 实现：OpenAI 兼容(AIClient) / 内网 HiAgent(HiAgentClient)。
 */
public interface AiChatClient {
    /** 是否可用（配置了 provider/且必要凭据齐备） */
    boolean enabled();

    /**
     * 单轮/多轮对话，返回模型文本内容（内部可能聚合 SSE）。
     * @return 模型文本；失败或不可用返回 null（上层降级本地规则）
     */
    String chat(String systemPrompt, List<Map<String, String>> history);

    /** 客户端名称，用于健康检查展示 */
    String name();
}