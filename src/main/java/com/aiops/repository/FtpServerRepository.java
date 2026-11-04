package com.aiops.repository;

import com.aiops.entity.FtpServer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FtpServerRepository extends JpaRepository<FtpServer, Long> {

    List<FtpServer> findAllByOrderByIdAsc();
}