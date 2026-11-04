package com.aiops.repository;

import com.aiops.entity.ExecRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

public interface ExecRunRepository extends JpaRepository<ExecRun, Long>, JpaSpecificationExecutor<ExecRun> {
    List<ExecRun> findTop100ByOrderByIdDesc();
}