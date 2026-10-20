package com.aiops.controller;

import com.aiops.common.PageResult;
import com.aiops.common.Result;
import com.aiops.entity.ServerAsset;
import com.aiops.service.ServerService;
import javax.validation.Valid;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 服务器资产管理控制器（CMDB）
 *
 * 接口列表：
 * - GET    /api/server/page       分页查询（keyword模糊搜索IP/主机名/OS/环境）
 * - GET    /api/server/{id}       获取单个服务器详情
 * - POST   /api/server            新增服务器
 * - PUT    /api/server/{id}       编辑服务器
 * - DELETE /api/server/{id}       删除服务器
 * - POST   /api/server/{id}/probe 探测指定服务器的SSH连接状态
 * - POST   /api/server/probe/all  探测所有服务器的SSH连接状态
 */
@RestController
@RequestMapping("/api/server")
@RequiredArgsConstructor
public class ServerController {

    private final ServerService serverService;

    // ==================== 查询 ====================

    /**
     * 分页查询服务器资产
     *
     * @param keyword  搜索关键词（模糊匹配IP/主机名/OS/环境）
     * @param page     页码，默认1
     * @param pageSize 每页条数，默认10，最大100
     */
    @GetMapping("/page")
    public Result<PageResult<ServerAsset>> page(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(1000) int pageSize) {

        PageResult<ServerAsset> result = serverService.pageQuery(keyword, page, pageSize);
        return Result.success(result);
    }

    /**
     * 获取服务器详情
     */
    @GetMapping("/{id}")
    public Result<ServerAsset> getById(@PathVariable Long id) {
        return Result.success(serverService.getDetail(id));
    }

    // ==================== 增删改 ====================

    /**
     * 新增服务器
     */
    @PostMapping
    public Result<ServerAsset> create(@Valid @RequestBody ServerAsset asset) {
        Long id = serverService.create(asset);
        return Result.success("新增成功", serverService.getDetail(id));
    }

    /**
     * 编辑服务器
     */
    @PutMapping("/{id}")
    public Result<ServerAsset> update(@PathVariable Long id, @Valid @RequestBody ServerAsset asset) {
        serverService.update(id, asset);
        return Result.success("更新成功", serverService.getDetail(id));
    }

    /**
     * 删除服务器
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        serverService.delete(id);
        return Result.success("删除成功", null);
    }

    // ==================== 状态探测 ====================

    /**
     * 探测指定服务器状态（查询 Prometheus 的 up 指标，非 SSH）
     */
    @PostMapping("/{id}/probe")
    public Result<ServerAsset> probe(@PathVariable Long id) {
        ServerAsset asset = serverService.probeStatus(id);
        String s = asset.getStatus();
        String statusText;
        if ("ONLINE".equals(s)) statusText = "在线";
        else if ("OFFLINE".equals(s)) statusText = "离线";
        else if ("MAINTENANCE".equals(s)) statusText = "维护中";
        else statusText = s;
        return Result.success("探测完成，状态: " + statusText, asset);
    }

    /**
     * 探测所有服务器状态（Prometheus up）
     */
    @PostMapping("/probe/all")
    public Result<List<ServerAsset>> probeAll() {
        List<ServerAsset> assets = serverService.probeAll();
        long online = assets.stream().filter(a -> "ONLINE".equals(a.getStatus())).count();
        return Result.success("批量探测完成，在线: " + online + "/" + assets.size(), assets);
    }

    /**
     * 从 Prometheus 一键同步服务器：发现 node_exporter 实例，
     * 新增未知节点、更新已知节点状态，消失的标记离线。
     */
    @PostMapping("/sync")
    public Result<java.util.Map<String, Object>> sync() {
        java.util.Map<String, Object> summary = serverService.syncFromPrometheus();
        String msg = String.valueOf(summary.getOrDefault("message", "同步完成"));
        return Result.success(msg, summary);
    }
}