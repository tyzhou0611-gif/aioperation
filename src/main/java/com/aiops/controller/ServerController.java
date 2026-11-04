package com.aiops.controller;

import com.aiops.common.PageResult;
import com.aiops.common.Result;
import com.aiops.entity.ServerAsset;
import com.aiops.service.AccessControlService;
import com.aiops.service.ServerImportService;
import com.aiops.service.ServerService;
import com.aiops.service.ServerSshService;
import com.aiops.service.ServerStatusService;
import javax.validation.Valid;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    private final ServerStatusService serverStatusService;
    private final ServerSshService serverSshService;
    private final ServerImportService serverImportService;
    private final AccessControlService accessControl;

    private String user(Authentication auth) { return auth == null ? "anonymous" : auth.getName(); }

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
            @RequestParam(required = false) Long projectId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(1000) int pageSize,
            Authentication auth) {

        PageResult<ServerAsset> result = serverService.pageQuery(keyword, projectId, page, pageSize, user(auth));
        return Result.success(result);
    }

    /**
     * 获取服务器详情
     */
    @GetMapping("/{id}")
    public Result<ServerAsset> getById(@PathVariable Long id, Authentication auth) {
        accessControl.assertServerAccess(user(auth), id);
        return Result.success(serverService.getDetail(id));
    }

    // ==================== 增删改 ====================

    /**
     * 新增服务器
     */
    @PostMapping
    public Result<ServerAsset> create(@Valid @RequestBody ServerAsset asset, Authentication auth) {
        accessControl.assertAdmin(user(auth));
        Long id = serverService.create(asset);
        ServerAsset detail = serverService.getDetail(id);
        // 新增后立即异步探测一次，避免新机器长时间显示“未知”（Prometheus 未采集时自动走 SSH/TCP/ICMP）
        serverStatusService.probeAsync(id);
        return Result.success("新增成功", detail);
    }

    /**
     * 复制服务器：以现有服务器为模板新增一条记录（密码/私钥可沿用源服务器）
     */
    @PostMapping("/{id}/clone")
    public Result<ServerAsset> clone(@PathVariable Long id, @Valid @RequestBody ServerAsset asset, Authentication auth) {
        accessControl.assertAdmin(user(auth));
        Long newId = serverService.clone(id, asset);
        ServerAsset detail = serverService.getDetail(newId);
        serverStatusService.probeAsync(newId);
        return Result.success("复制成功", detail);
    }

    /**
     * 编辑服务器
     */
    @PutMapping("/{id}")
    public Result<ServerAsset> update(@PathVariable Long id, @Valid @RequestBody ServerAsset asset, Authentication auth) {
        accessControl.assertAdmin(user(auth));
        serverService.update(id, asset);
        ServerAsset detail = serverService.getDetail(id);
        // IP/凭据可能已变化，重新探测状态
        serverStatusService.probeAsync(id);
        return Result.success("更新成功", detail);
    }

    /**
     * 删除服务器
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id, Authentication auth) {
        accessControl.assertAdmin(user(auth));
        serverService.delete(id);
        return Result.success("删除成功", null);
    }

    // ==================== SSH 连接测试 / 自动探测 ====================

    /** 使用表单凭据测试 SSH 连接（无需先保存） */
    @PostMapping("/ssh/test")
    public Result<Map<String, Object>> sshTest(@RequestBody Map<String, Object> body) {
        Map<String, Object> data = serverSshService.test(body);
        return Result.success(String.valueOf(data.get("message")), data);
    }

    /** 使用表单凭据探测服务器信息（hostname/os/cpu/memory/disk） */
    @PostMapping("/ssh/probe")
    public Result<Map<String, Object>> sshProbe(@RequestBody Map<String, Object> body) {
        Map<String, Object> data = serverSshService.probe(body);
        return Result.success(String.valueOf(data.get("message")), data);
    }

    /** 使用服务器已保存的密码/私钥测试 SSH */
    @PostMapping("/{id}/ssh/test")
    public Result<Map<String, Object>> sshTestSaved(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body, Authentication auth) {
        accessControl.assertServerAccess(user(auth), id);
        Map<String, Object> req = body == null ? new HashMap<String, Object>() : new HashMap<String, Object>(body);
        req.put("id", id);
        Map<String, Object> data = serverSshService.test(req);
        return Result.success(String.valueOf(data.get("message")), data);
    }

    /** 使用服务器已保存的密码/私钥探测信息 */
    @PostMapping("/{id}/ssh/probe")
    public Result<Map<String, Object>> sshProbeSaved(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body, Authentication auth) {
        accessControl.assertServerAccess(user(auth), id);
        Map<String, Object> req = body == null ? new HashMap<String, Object>() : new HashMap<String, Object>(body);
        req.put("id", id);
        Map<String, Object> data = serverSshService.probe(req);
        return Result.success(String.valueOf(data.get("message")), data);
    }

    // ==================== Shell 工具导入 ====================

    /** 解析 Xshell/SecureCRT/SSH config/CSV/JSON，返回候选预览 */
    @PostMapping("/import/preview")
    public Result<Map<String, Object>> importPreview(@RequestParam("files") MultipartFile[] files, Authentication auth) throws Exception {
        accessControl.assertAdmin(user(auth));
        return Result.success(serverImportService.preview(files));
    }

    /** 确认导入：创建/覆盖服务器 */
    @SuppressWarnings("unchecked")
    @PostMapping("/import/confirm")
    public Result<Map<String, Object>> importConfirm(@RequestBody Map<String, Object> body, Authentication auth) {
        accessControl.assertAdmin(user(auth));
        String importId = body.get("importId") == null ? null : body.get("importId").toString();
        boolean overwrite = Boolean.TRUE.equals(body.get("overwrite"));
        String password = body.get("password") == null ? null : body.get("password").toString();
        List<Integer> selected = new ArrayList<Integer>();
        Object raw = body.get("selected");
        if (raw instanceof List) {
            for (Object o : (List<Object>) raw) {
                try { selected.add(Integer.valueOf(o.toString())); } catch (Exception ignore) {}
            }
        }
        Map<String, Object> data = serverImportService.confirm(importId, selected, overwrite, password);
        // 导入完成后异步探测新增机器的状态，避免长时间显示“未知”
        Object addedIds = data.get("addedIds");
        if (addedIds instanceof List) {
            for (Object o : (List<Object>) addedIds) {
                try {
                    serverStatusService.probeAsync(Long.valueOf(o.toString()));
                } catch (Exception ignore) { }
            }
        }
        return Result.success("导入完成", data);
    }

    // ==================== 状态探测 ====================

    /**
     * 探测指定服务器状态（来源：Prometheus / SSH / ICMP）
     */
    @PostMapping("/{id}/probe")
    public Result<ServerAsset> probe(@PathVariable Long id, Authentication auth) {
        accessControl.assertServerAccess(user(auth), id);
        ServerAsset asset = serverStatusService.probe(id);
        String s = asset.getStatus();
        String statusText;
        if ("ONLINE".equals(s)) statusText = "在线";
        else if ("OFFLINE".equals(s)) statusText = "离线";
        else if ("MAINTENANCE".equals(s)) statusText = "维护中";
        else statusText = s;
        return Result.success("探测完成（" + sourceText(serverStatusService.currentSource()) + "），状态: " + statusText, asset);
    }

    private String sourceText(String source) {
        if ("auto".equals(source)) return "自动";
        if ("prometheus".equals(source)) return "Prometheus";
        if ("ssh".equals(source)) return "SSH";
        if ("icmp".equals(source)) return "ICMP";
        return source;
    }

    /**
     * 探测当前用户可见的全部服务器状态（来源：Prometheus / SSH / ICMP）
     */
    @PostMapping("/probe/all")
    public Result<List<ServerAsset>> probeAll(Authentication auth) {
        List<ServerAsset> assets = serverStatusService.probeAll(accessControl.accessibleServerIds(user(auth)));
        long online = assets.stream().filter(a -> "ONLINE".equals(a.getStatus())).count();
        return Result.success("批量探测完成（" + sourceText(serverStatusService.currentSource()) + "），在线: " + online + "/" + assets.size(), assets);
    }

    /**
     * 从 Prometheus 一键同步服务器：发现 node_exporter 实例，
     * 新增未知节点、更新已知节点状态，消失的标记离线。
     */
    @PostMapping("/sync")
    public Result<java.util.Map<String, Object>> sync(Authentication auth) {
        accessControl.assertAdmin(user(auth));
        java.util.Map<String, Object> summary = serverService.syncFromPrometheus();
        String msg = String.valueOf(summary.getOrDefault("message", "同步完成"));
        return Result.success(msg, summary);
    }
}