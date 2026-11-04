package com.aiops.repository;

import com.aiops.entity.MetricSample;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MetricSampleRepository extends JpaRepository<MetricSample, Long> {

    List<MetricSample> findByMetricCodeAndSampleTimeBetweenOrderBySampleTimeAsc(
            String metricCode, Long start, Long end, Pageable pageable);

    long countByMetricCodeAndSampleTimeBetween(String metricCode, Long start, Long end);

    long deleteBySampleTimeBefore(Long time);
}