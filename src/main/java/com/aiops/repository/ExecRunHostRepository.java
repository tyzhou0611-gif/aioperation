package com.aiops.repository;

import com.aiops.entity.ExecRunHost;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExecRunHostRepository extends JpaRepository<ExecRunHost, Long> {
    List<ExecRunHost> findByRunIdOrderByIdAsc(Long runId);
    List<ExecRunHost> findByServerId(Long serverId);
    void deleteByRunId(Long runId);
}