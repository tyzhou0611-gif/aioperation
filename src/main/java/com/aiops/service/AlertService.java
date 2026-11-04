package com.aiops.service;

import com.aiops.common.PageResult;
import com.aiops.common.ServiceException;
import com.aiops.entity.AlertHistory;
import com.aiops.entity.AlertRule;
import com.aiops.repository.AlertHistoryRepository;
import com.aiops.repository.AlertRuleRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import javax.annotation.PostConstruct;
import javax.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 告警服务
 * 告警规则CRUD + 告警历史查询
 */
@Service
@RequiredArgsConstructor
public class AlertService {

    private final AlertRuleRepository ruleRepository;
    private final AlertHistoryRepository historyRepository;
    private final MeterRegistry meterRegistry;

    private Counter alertTriggerCounter;

    @javax.annotation.PostConstruct
    public void initMetrics() {
        alertTriggerCounter = Counter.builder("aiops.alert.triggered.total")
                .description("告警触发总次数")
                .register(meterRegistry);
    }

    // ==================== 告警规则 ====================

    /** 分页查询规则 */
    public PageResult<AlertRule> pageRules(String keyword, int page, int pageSize) {
        Specification<AlertRule> spec = (root, query, cb) -> {
            if (keyword == null || keyword.trim().isEmpty()) {
                return cb.conjunction();
            }
            String pattern = "%" + keyword.trim() + "%";
            return cb.or(
                    cb.like(root.get("ruleName"), pattern),
                    cb.like(root.get("metricName"), pattern)
            );
        };
        PageRequest pageRequest = PageRequest.of(page - 1, pageSize,
                Sort.by(Sort.Direction.DESC, "createTime"));
        return PageResult.of(ruleRepository.findAll(spec, pageRequest));
    }

    /** 获取所有启用的规则 */
    public List<AlertRule> getEnabledRules() {
        return ruleRepository.findByEnabled(1);
    }

    /** 获取单个规则 */
    public AlertRule getRuleById(Long id) {
        return ruleRepository.findById(id)
                .orElseThrow(() -> new ServiceException("告警规则不存在"));
    }

    /** 新增规则 */
    @Transactional
    public AlertRule createRule(AlertRule rule) {
        return ruleRepository.save(rule);
    }

    /** 更新规则 */
    @Transactional
    public AlertRule updateRule(Long id, AlertRule update) {
        AlertRule existing = getRuleById(id);
        existing.setRuleName(update.getRuleName());
        existing.setMetricName(update.getMetricName());
        existing.setComparator(update.getComparator());
        existing.setThreshold(update.getThreshold());
        existing.setLevel(update.getLevel());
        existing.setNotifyEmail(update.getNotifyEmail());
        existing.setEnabled(update.getEnabled());
        return ruleRepository.save(existing);
    }

    /** 删除规则 */
    @Transactional
    public void deleteRule(Long id) {
        if (!ruleRepository.existsById(id)) {
            throw new ServiceException("告警规则不存在");
        }
        ruleRepository.deleteById(id);
    }

    // ==================== 告警历史 ====================

    /** 分页查询告警历史 */
    public PageResult<AlertHistory> pageHistory(String level, String keyword, int page, int pageSize) {
        Specification<AlertHistory> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (level != null && !level.trim().isEmpty()) {
                predicates.add(cb.equal(root.get("level"), level));
            }
            if (keyword != null && !keyword.trim().isEmpty()) {
                String pattern = "%" + keyword.trim() + "%";
                predicates.add(cb.or(
                        cb.like(root.get("serverIp"), pattern),
                        cb.like(root.get("metricName"), pattern),
                        cb.like(root.get("message"), pattern)
                ));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        PageRequest pageRequest = PageRequest.of(page - 1, pageSize,
                Sort.by(Sort.Direction.DESC, "createTime"));
        return PageResult.of(historyRepository.findAll(spec, pageRequest));
    }

    /** 创建告警记录 */
    @Transactional
    public AlertHistory createHistory(AlertHistory history) {
        alertTriggerCounter.increment();
        return historyRepository.save(history);
    }
}