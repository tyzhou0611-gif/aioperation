package com.aiops.repository;

import com.aiops.entity.MiddlewareAsset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

public interface MiddlewareAssetRepository extends JpaRepository<MiddlewareAsset, Long>,
        JpaSpecificationExecutor<MiddlewareAsset> {
    List<MiddlewareAsset> findByType(String type);
    List<MiddlewareAsset> findByServerId(Long serverId);
}