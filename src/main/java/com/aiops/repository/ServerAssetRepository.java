package com.aiops.repository;

import com.aiops.entity.ServerAsset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ServerAssetRepository extends JpaRepository<ServerAsset, Long>,
        JpaSpecificationExecutor<ServerAsset> {

    List<ServerAsset> findByStatus(String status);

    List<ServerAsset> findByHostnameContaining(String hostname);

    List<ServerAsset> findByIpContaining(String ip);
}