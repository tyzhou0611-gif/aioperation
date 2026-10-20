package com.aiops.repository;

import com.aiops.entity.SysConfig;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SysConfigRepository extends JpaRepository<SysConfig, Long> {
    SysConfig findByConfigKey(String configKey);
}