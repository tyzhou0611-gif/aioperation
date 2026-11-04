package com.aiops.repository;

import com.aiops.entity.ServerAgent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ServerAgentRepository extends JpaRepository<ServerAgent, Long> {

    Optional<ServerAgent> findByServerIdAndExporter(Long serverId, String exporter);

    List<ServerAgent> findByServerId(Long serverId);

    List<ServerAgent> findByStatus(String status);
}