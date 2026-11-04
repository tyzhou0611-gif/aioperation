package com.aiops.controller;

import com.aiops.common.PageResult;
import com.aiops.common.Result;
import com.aiops.entity.AlertHistory;
import com.aiops.entity.AlertRule;
import com.aiops.service.AlertService;
import javax.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 告警管理控制器
 *
 * 告警规则 CRUD：
 *   GET    /api/alert/rule/page   分页查询规则
 *   GET    /api/alert/rule/{id}   获取规则详情
 *   POST   /api/alert/rule        新增规则
 *   PUT    /api/alert/rule/{id}   编辑规则
 *   DELETE /api/alert/rule/{id}   删除规则
 *
 * 告警历史：
 *   GET    /api/alert/history/page  分页查询历史（支持按级别筛选）
 */
@RestController
@RequestMapping("/api/alert")
@RequiredArgsConstructor
public class AlertController {

    private final AlertService alertService;

    // ==================== 告警规则 ====================

    @GetMapping("/rule/page")
    public Result<PageResult<AlertRule>> rulePage(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        return Result.success(alertService.pageRules(keyword, page, pageSize));
    }

    @GetMapping("/rule/{id}")
    public Result<AlertRule> ruleDetail(@PathVariable Long id) {
        return Result.success(alertService.getRuleById(id));
    }

    @PostMapping("/rule")
    public Result<AlertRule> ruleCreate(@Valid @RequestBody AlertRule rule) {
        return Result.success("新增成功", alertService.createRule(rule));
    }

    @PutMapping("/rule/{id}")
    public Result<AlertRule> ruleUpdate(@PathVariable Long id, @Valid @RequestBody AlertRule rule) {
        return Result.success("更新成功", alertService.updateRule(id, rule));
    }

    @DeleteMapping("/rule/{id}")
    public Result<Void> ruleDelete(@PathVariable Long id) {
        alertService.deleteRule(id);
        return Result.success("删除成功", null);
    }

    // ==================== 告警历史 ====================

    @GetMapping("/history/page")
    public Result<PageResult<AlertHistory>> historyPage(
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        return Result.success(alertService.pageHistory(level, keyword, page, pageSize));
    }
}