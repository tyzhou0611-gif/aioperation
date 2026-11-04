package com.aiops.infrastructure.ai;

import java.util.List;
import java.util.Map;

/**
 * 大模型对话客户端统一接口。
 * 实现：OpenAI 兼容(AIClient) / Anthropic(AnthropicChatClient) / 内网 HiAgent(HiAgentClient)。
 */
public interface AiChatClient {
    /** 是否可用（配置了 provider/且必要凭据齐备） */
    boolean enabled();

    /**
     * 单轮/多轮对话，返回模型文本内容（内部可能聚合 SSE）。
     * @return 模型文本；失败或不可用返回 null（上层降级本地规则）
     */
    String chat(String systemPrompt, List<Map<String, String>> history);

    /**
     * 对话并返回模型可见的思考内容（如 Anthropic thinking block）。
     * 默认实现忽略思考内容，兼容不支持 reasoning 的模型。
     */
    default ChatReply chatWithThinking(String systemPrompt, List<Map<String, String>> history) {
        return new ChatReply(chat(systemPrompt, history), null);
    }

    /** 客户端名称，用于健康检查展示 */
    String name();

    /**
     * 最近一次调用失败的原因（成功或无错误时为 null）。
     * 设置页测试连接会把该信息透出，便于区分网络、鉴权、服务端未开启等问题。
     */
    default String lastError() { return null; }

    /** 一次对话的结果：正文 + 可选的模型思考内容 */
    class ChatReply {
        private final String text;
        private final String thinking;

        public ChatReply(String text, String thinking) {
            this.text = text;
            this.thinking = thinking;
        }

        public String getText() { return text; }
        public String getThinking() { return thinking; }
    }
}