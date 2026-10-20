package com.aiops.repository;

import com.aiops.entity.AppAsset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

public interface AppAssetRepository extends JpaRepository<AppAsset, Long>,
        JpaSpecificationExecutor<AppAsset> {
    Optional<AppAsset> findByCode(String code);
    boolean existsByCode(String code);
    List<AppAsset> findByServerId(Long serverId);
}