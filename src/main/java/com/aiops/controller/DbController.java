package com.aiops.controller;

import com.aiops.common.Result;
import com.aiops.common.ServiceException;
import com.aiops.controller.vo.DbExecuteRequest;
import com.aiops.entity.DbConnection;
import com.aiops.entity.DbExecuteLog;
import com.aiops.service.DbWorkbenchService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SQL 工作台 API
 *
 * 连接统一来自【资产管理 → 中间件/数据库管理 → 数据库类】，工作台不再支持手工新建/编辑/删除。
 * - GET  /api/db/connections                连接列表（读取前自动同步资产）
 * - POST /api/db/connections/sync           手动触发同步
 * - POST /api/db/connections/{id}/test      连通性测试
 * - GET  /api/db/connections/{id}/tables    表列表
 * - GET  /api/db/connections/{id}/tables/{t}/columns  表结构
 * - GET  /api/db/connections/{id}/logs      执行历史
 * - POST /api/db/check-dangerous            危险 SQL 检查
 * - POST /api/db/execute                    执行 SQL
 */
@RestController
@RequestMapping("/api/db")
@RequiredArgsConstructor
public class DbController {

    private final DbWorkbenchService dbWorkbenchService;

    @GetMapping("/connections")
    public Result<List<DbConnection>> connections() {
        dbWorkbenchService.syncFromMiddleware();
        return Result.success(dbWorkbenchService.listConnections());
    }

    /** 手动同步资产 → 工作台连接 */
    @PostMapping("/connections/sync")
    public Result<Map<String, Object>> sync() {
        return Result.success(dbWorkbenchService.syncFromMiddleware());
    }

    @PostMapping("/connections/{id}/test")
    public Result<Map<String, Object>> test(@PathVariable Long id) {
        return Result.success(dbWorkbenchService.testConnection(id));
    }

    @GetMapping("/connections/{id}/databases")
    public Result<List<String>> databases(@PathVariable Long id) {
        try {
            return Result.success(dbWorkbenchService.listDatabases(id));
        } catch (Exception e) {
            throw new ServiceException("获取数据库列表失败: " + e.getMessage());
        }
    }

    @GetMapping("/connections/{id}/tables")
    public Result<List<Map<String, String>>> tables(@PathVariable Long id,
                                                    @RequestParam(required = false) String database) {
        try {
            return Result.success(dbWorkbenchService.listTables(id, database));
        } catch (Exception e) {
            throw new ServiceException("获取表列表失败: " + e.getMessage());
        }
    }

    @GetMapping("/connections/{id}/tables/{table}/columns")
    public Result<List<Map<String, String>>> columns(@PathVariable Long id, @PathVariable String table,
                                                      @RequestParam(required = false) String database) {
        try {
            return Result.success(dbWorkbenchService.tableSchema(id, table, database));
        } catch (Exception e) {
            throw new ServiceException("获取表结构失败: " + e.getMessage());
        }
    }

    @GetMapping("/connections/{id}/logs")
    public Result<List<DbExecuteLog>> logs(@PathVariable Long id,
                                            @RequestParam(defaultValue = "100") int limit) {
        return Result.success(dbWorkbenchService.listExecLogs(id, limit));
    }

    @PostMapping("/check-dangerous")
    public Result<Map<String, Object>> checkDangerous(@RequestBody Map<String, String> body) {
        String sql = body.get("sql");
        String warning = dbWorkbenchService.checkDangerous(sql);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("dangerous", warning != null);
        result.put("message", warning);
        return Result.success(result);
    }

    @PostMapping("/execute")
    public Result<Map<String, Object>> execute(@RequestBody DbExecuteRequest req, Authentication auth) {
        if (req.getConnectionId() == null) throw new ServiceException("请选择数据库连接");
        if (req.getSql() == null || req.getSql().trim().isEmpty()) throw new ServiceException("SQL不能为空");
        int maxRows = req.getMaxRows() == null ? 500 : req.getMaxRows();
        String operator = auth != null && auth.getPrincipal() != null
                ? auth.getPrincipal().toString() : "unknown";
        return Result.success(dbWorkbenchService.executeSql(
                req.getConnectionId(), req.getSql(), maxRows, operator, req.getDatabase()));
    }
}