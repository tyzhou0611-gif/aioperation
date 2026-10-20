package com.aiops.repository;

import com.aiops.entity.DbExecuteLog;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface DbExecuteLogRepository extends JpaRepository<DbExecuteLog, Long> {
    List<DbExecuteLog> findByConnectionIdOrderByIdDesc(Long connectionId);
}