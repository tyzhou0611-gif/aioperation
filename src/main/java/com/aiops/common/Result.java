package com.aiops.common;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 统一API响应实体
 * code=200 成功，code=500 业务失败，code=401 未授权
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Result<T> {

    private int code;
    private String msg;
    private T data;
    private LocalDateTime timestamp;

    // ==================== 成功 ====================

    public static <T> Result<T> success() {
        return build(200, "success", null);
    }

    public static <T> Result<T> success(T data) {
        return build(200, "success", data);
    }

    public static <T> Result<T> success(String msg, T data) {
        return build(200, msg, data);
    }

    // ==================== 失败 ====================

    public static <T> Result<T> error(String msg) {
        return build(500, msg, null);
    }

    public static <T> Result<T> error(int code, String msg) {
        return build(code, msg, null);
    }

    // ==================== 未授权 ====================

    public static <T> Result<T> unauthorized(String msg) {
        return build(401, msg, null);
    }

    // ==================== 内部构建 ====================

    private static <T> Result<T> build(int code, String msg, T data) {
        return Result.<T>builder()
                .code(code)
                .msg(msg)
                .data(data)
                .timestamp(LocalDateTime.now())
                .build();
    }
}