package com.aiops.repository;

import com.aiops.entity.DbConnection;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface DbConnectionRepository extends JpaRepository<DbConnection, Long> {
    List<DbConnection> findAllByOrderByIdDesc();
}