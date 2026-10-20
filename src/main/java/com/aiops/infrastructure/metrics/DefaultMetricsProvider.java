package com.aiops.infrastructure.metrics;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 默认指标提供者（本地模拟降级方案）
 *
 * 当未配置外部 Prometheus 时，使用随机算法模拟服务器指标数据，
 * 确保黑盒开箱即用、零外部依赖。
 *
 * 标注 {@link ConditionalOnMissingBean}：当容器中没有其他 MetricsProvider Bean 时自动生效。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.metrics.source", havingValue = "default", matchIfMissing = true)
public class DefaultMetricsProvider implements MetricsProvider {

    private final Random random = new Random();

    @Override
    public double getCpuUsage(Long serverId) {
        double base = 40 + random.nextDouble() * 30;
        return clamp(base + random.nextDouble() * 20);
    }

    @Override
    public double getMemoryUsage(Long serverId) {
        return clamp(60 + random.nextDouble() * 25);
    }

    @Override
    public double getDiskUsage(Long serverId) {
        return clamp(40 + random.nextDouble() * 40);
    }

    @Override
    public String getServerStatus(Long serverId) {
        // 默认返回ONLINE，由ServerService的probeStatus真实探测
        return "ONLINE";
    }

    @Override
    public MetricTrend getCpuUsageTrend(Long serverId, int hours) {
        return generateTrend(hours, 40, 30, true);
    }

    @Override
    public MetricTrend getMemoryUsageTrend(Long serverId, int hours) {
        return generateTrend(hours, 60, 20, false);
    }

    /**
     * 生成模拟趋势数据
     */
    private MetricTrend generateTrend(int hours, double baseValue, double amplitude, boolean isCpu) {
        if (hours <= 0) hours = 24;
        List<String> times = new ArrayList<>();
        List<Double> values = new ArrayList<>();

        LocalDateTime now = LocalDateTime.now();
        Random rnd = new Random(42); // 固定种子保证可复现

        for (int i = hours - 1; i >= 0; i--) {
            LocalDateTime point = now.minusHours(i);
            times.add(String.format("%02d:%02d", point.getHour(), point.getMinute()));

            double value = baseValue + rnd.nextDouble() * amplitude;
            if (isCpu) {
                // CPU 工作时段偏高
                if (point.getHour() >= 9 && point.getHour() <= 18) value += 12;
                if (point.getHour() >= 22 || point.getHour() <= 5) value -= 8;
            } else {
                // 内存工作时段略高
                if (point.getHour() >= 10 && point.getHour() <= 17) value += 6;
            }
            values.add(Math.round(clamp(value) * 10.0) / 10.0);
        }

        return MetricTrend.builder()
                .times(times)
                .values(values)
                .build();
    }

    private double clamp(double v) {
        return Math.min(Math.max(v, 0), 99.9);
    }
}