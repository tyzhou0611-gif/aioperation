package com.aiops.repository;

import com.aiops.entity.CommandTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CommandTemplateRepository extends JpaRepository<CommandTemplate, Long> {
    List<CommandTemplate> findByTargetType(String targetType);
    List<CommandTemplate> findByTargetTypeAndMiddlewareType(String targetType, String middlewareType);
}