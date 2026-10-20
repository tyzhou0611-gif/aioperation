package com.aiops.repository;

import com.aiops.entity.DiagnosisHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface DiagnosisHistoryRepository extends JpaRepository<DiagnosisHistory, Long>, JpaSpecificationExecutor<DiagnosisHistory> {}