package com.aiops.repository;

import com.aiops.entity.SimulatedLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface SimulatedLogRepository extends JpaRepository<SimulatedLog, Long>, JpaSpecificationExecutor<SimulatedLog> {}