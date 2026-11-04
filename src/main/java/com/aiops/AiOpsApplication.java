package com.aiops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * AI-Ops Platform 启动类
 * 黑盒部署：一个Jar包同时包含后端和前端（Vue打包后的static资源）
 */
@SpringBootApplication
@EnableCaching
@EnableScheduling
@EnableAspectJAutoProxy
public class AiOpsApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiOpsApplication.class, args);
    }
}