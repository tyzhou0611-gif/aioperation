package com.aiops.repository;

import com.aiops.entity.MetricDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MetricDefinitionRepository extends JpaRepository<MetricDefinition, Long> {

    Optional<MetricDefinition> findByCode(String code);

    boolean existsByCode(String code);

    List<MetricDefinition> findAllByOrderByBuiltinDescNameAsc();

    List<MetricDefinition> findByEnabledOrderByBuiltinDescNameAsc(Integer enabled);
}