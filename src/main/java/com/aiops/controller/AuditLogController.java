package com.aiops.controller;

import com.aiops.common.PageResult;
import com.aiops.common.Result;
import com.aiops.entity.AuditLog;
import com.aiops.repository.AuditLogRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/audit")
public class AuditLogController {
    private final AuditLogRepository repository;
    public AuditLogController(AuditLogRepository r) { this.repository = r; }

    @GetMapping("/page")
    public Result<PageResult<AuditLog>> page(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        org.springframework.data.domain.Page<com.aiops.entity.AuditLog> all = repository.findAll(PageRequest.of(page - 1, size,
                Sort.by(Sort.Direction.DESC, "createTime")));
        return Result.success(PageResult.of(all));
    }
}