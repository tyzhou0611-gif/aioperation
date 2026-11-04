package com.aiops.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Caffeine 缓存配置
 *
 * 配置说明：
 * - maximumSize: 最大缓存条数 10000
 * - expireAfterWrite: 写入后1小时过期
 *
 * 切换到Redis缓存时，只需在application-prod.yml中：
 * 1. 配置spring.redis
 * 2. 将spring.cache.type改为redis
 * 3. 注释掉此@Configuration或通过@Profile控制
 */
@Configuration
public class CacheConfig {

    @Bean
    public Caffeine<Object, Object> caffeineSpec() {
        return Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(1, TimeUnit.HOURS)
                .recordStats();
    }

    @Bean
    public CacheManager cacheManager(Caffeine<Object, Object> caffeine) {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();
        cacheManager.setCaffeine(caffeine);
        return cacheManager;
    }
}