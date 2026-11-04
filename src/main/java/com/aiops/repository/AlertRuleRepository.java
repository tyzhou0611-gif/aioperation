package com.aiops.repository;

import com.aiops.entity.AlertRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AlertRuleRepository extends JpaRepository<AlertRule, Long>,
        JpaSpecificationExecutor<AlertRule> {

    List<AlertRule> findByEnabled(Integer enabled);
}