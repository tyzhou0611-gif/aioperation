package com.aiops.repository;

import com.aiops.entity.AiSolutionTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AiSolutionTemplateRepository extends JpaRepository<AiSolutionTemplate, Long> {
    List<AiSolutionTemplate> findAllByOrderByIdAsc();
}