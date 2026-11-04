package com.aiops.infrastructure.embedding;

import org.springframework.stereotype.Component;
import java.util.*;

/**
 * 关键词向量提供者（黑盒默认）。
 * 将文本分词后转为稀疏词频向量，供 BM25 余弦相似度检索。
 * 完全离线、纯 Java，无外部依赖。
 */
@Component
public class KeywordEmbeddingProvider implements EmbeddingProvider {

    @Override
    public float[] embed(String text) {
        if (text == null || text.isEmpty()) return new float[0];
        // 简单分词：中文按字、英文按空格
        List<String> tokens = tokenize(text);
        Map<String, Integer> freq = new HashMap<String, Integer>();
        for (String t : tokens) freq.merge(t, 1, Integer::sum);
        // 返回词频数组（按词排序，稀疏表示转为定长哈希桶）
        float[] vec = new float[256];
        for (Map.Entry<String, Integer> e : freq.entrySet()) {
            int bucket = Math.abs(e.getKey().hashCode()) % 256;
            vec[bucket] += e.getValue();
        }
        // L2 归一化
        float norm = 0;
        for (float v : vec) norm += v * v;
        norm = (float) Math.sqrt(norm);
        if (norm > 0) for (int i = 0; i < vec.length; i++) vec[i] /= norm;
        return vec;
    }

    @Override public boolean isAvailable() { return true; }
    @Override public String name() { return "keyword"; }

    /** 通用分词：中文逐字 + 英文/数字连续串 */
    public static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                cur.append(c);
            } else {
                if (cur.length() > 0) { tokens.add(cur.toString().toLowerCase()); cur.setLength(0); }
                if (isCJK(c)) tokens.add(String.valueOf(c));
            }
        }
        if (cur.length() > 0) tokens.add(cur.toString().toLowerCase());
        return tokens;
    }

    private static boolean isCJK(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }

    /** 余弦相似度 */
    public static float cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0) return 0;
        int len = Math.min(a.length, b.length);
        float dot = 0;
        for (int i = 0; i < len; i++) dot += a[i] * b[i];
        return dot; // 已归一化，dot 即 cosine
    }
}