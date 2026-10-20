package com.aiops.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * JSON 数组字段与 List<Long> 的解析工具（资产依赖关系存储为 JSON 数组字符串）
 */
public final class JsonIdUtils {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonIdUtils() {}

    /** 解析 "[1,2,3]" → List<Long>；null/非法返回空列表 */
    public static List<Long> parse(String json) {
        if (json == null || json.trim().isEmpty()) return new ArrayList<>();
        try {
            List<Long> list = MAPPER.readValue(json, new TypeReference<List<Long>>() {});
            return list == null ? new ArrayList<>() : list;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** List<Long> → JSON 字符串 */
    public static String toJson(List<Long> ids) {
        try {
            return MAPPER.writeValueAsString(ids == null ? new ArrayList<>() : ids);
        } catch (Exception e) {
            return "[]";
        }
    }
}