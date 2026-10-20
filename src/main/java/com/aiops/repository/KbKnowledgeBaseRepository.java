package com.aiops.repository;

import com.aiops.entity.KbKnowledgeBase;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KbKnowledgeBaseRepository extends JpaRepository<KbKnowledgeBase, Long> {}