package com.aiops.repository;

import com.aiops.entity.AlertHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AlertHistoryRepository extends JpaRepository<AlertHistory, Long>,
        JpaSpecificationExecutor<AlertHistory> {

    long countByCreateTimeAfter(LocalDateTime after);

    List<AlertHistory> findByStatus(String status);
}