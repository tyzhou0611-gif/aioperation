package com.aiops.infrastructure.embedding;

/**
 * 向量化提供者接口。
 * 实现类：KeywordEmbeddingProvider（黑盒默认，BM25关键词）、OpenAIEmbeddingProvider（外接）
 */
public interface EmbeddingProvider {
    float[] embed(String text);
    boolean isAvailable();
    String name();
}