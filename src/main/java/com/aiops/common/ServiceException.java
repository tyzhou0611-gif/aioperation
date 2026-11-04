package com.aiops.common;

import lombok.Getter;

/**
 * 业务异常
 * 在Service层抛出，由GlobalExceptionHandler统一捕获处理
 */
@Getter
public class ServiceException extends RuntimeException {

    private final int code;

    public ServiceException(String message) {
        super(message);
        this.code = 500;
    }

    public ServiceException(int code, String message) {
        super(message);
        this.code = code;
    }

    public ServiceException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }
}