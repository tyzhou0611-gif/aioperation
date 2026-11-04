package com.aiops.repository;

import com.aiops.entity.ElkConnection;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ElkConnectionRepository extends JpaRepository<ElkConnection, Long> {
    List<ElkConnection> findAllByOrderByIdDesc();
}