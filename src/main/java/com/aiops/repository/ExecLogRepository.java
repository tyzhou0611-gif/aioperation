package com.aiops.repository;

import com.aiops.entity.ExecLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ExecLogRepository extends JpaRepository<ExecLog, Long>, JpaSpecificationExecutor<ExecLog> {
}