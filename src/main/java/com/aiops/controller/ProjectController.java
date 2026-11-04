package com.aiops.controller;

import com.aiops.common.Result;
import com.aiops.controller.vo.ProjectAssetsRequest;
import com.aiops.controller.vo.ProjectVO;
import com.aiops.entity.Project;
import com.aiops.service.AccessControlService;
import com.aiops.service.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 项目管理 API
 *
 * - GET    /api/project/list          项目列表（含资产数量）
 * - GET    /api/project/options       大屏项目下拉选项
 * - GET    /api/project/{id}          项目详情
 * - POST   /api/project               新增项目
 * - PUT    /api/project/{id}          修改项目
 * - DELETE /api/project/{id}          删除项目（同时删除资产关联）
 * - GET    /api/project/{id}/assets   项目已关联资产
 * - PUT    /api/project/{id}/assets   全量替换项目资产
 */
@RestController
@RequestMapping("/api/project")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;
    private final AccessControlService accessControl;

    private String user(Authentication auth) { return auth == null ? "anonymous" : auth.getName(); }

    @GetMapping("/list")
    public Result<List<ProjectVO>> list(@RequestParam(required = false) String keyword, Authentication auth) {
        return Result.success(projectService.list(keyword, user(auth)));
    }

    @GetMapping("/options")
    public Result<List<Map<String, Object>>> options(Authentication auth) {
        return Result.success(projectService.options(user(auth)));
    }

    @GetMapping("/{id}")
    public Result<ProjectVO> detail(@PathVariable Long id, Authentication auth) {
        return Result.success(projectService.detail(id, user(auth)));
    }

    @PostMapping
    public Result<Project> create(@RequestBody Project project, Authentication auth) {
        accessControl.assertAdmin(user(auth));
        return Result.success("新增成功", projectService.create(project));
    }

    @PutMapping("/{id}")
    public Result<Project> update(@PathVariable Long id, @RequestBody Project project, Authentication auth) {
        accessControl.assertAdmin(user(auth));
        return Result.success("更新成功", projectService.update(id, project));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id, Authentication auth) {
        accessControl.assertAdmin(user(auth));
        projectService.delete(id);
        return Result.success("删除成功", null);
    }

    @GetMapping("/{id}/assets")
    public Result<Map<String, List<Long>>> assets(@PathVariable Long id, Authentication auth) {
        accessControl.assertAdmin(user(auth));
        return Result.success(projectService.assets(id));
    }

    @PutMapping("/{id}/assets")
    public Result<Void> replaceAssets(@PathVariable Long id, @RequestBody ProjectAssetsRequest req, Authentication auth) {
        accessControl.assertAdmin(user(auth));
        projectService.replaceAssets(id, req.getServerIds(), req.getMiddlewareIds(), req.getAppIds());
        return Result.success("资产分配已保存", null);
    }

    /** 设置项目可见角色（管理员） */
    @SuppressWarnings("unchecked")
    @PutMapping("/{id}/roles")
    public Result<Void> setRoles(@PathVariable Long id, @RequestBody Map<String, Object> body, Authentication auth) {
        accessControl.assertAdmin(user(auth));
        List<Long> roleIds = new ArrayList<Long>();
        Object raw = body.get("roleIds");
        if (raw instanceof List) {
            for (Object o : (List<Object>) raw) {
                try { roleIds.add(Long.valueOf(o.toString())); } catch (Exception ignore) {}
            }
        }
        projectService.setRoles(id, roleIds);
        return Result.success("可见角色已保存", null);
    }

    /** 设置服务器所属项目（管理员，服务器管理页使用） */
    @SuppressWarnings("unchecked")
    @PutMapping("/server/{serverId}")
    public Result<Void> setServerProjects(@PathVariable Long serverId, @RequestBody Map<String, Object> body, Authentication auth) {
        accessControl.assertAdmin(user(auth));
        List<Long> projectIds = new ArrayList<Long>();
        Object raw = body.get("projectIds");
        if (raw instanceof List) {
            for (Object o : (List<Object>) raw) {
                try { projectIds.add(Long.valueOf(o.toString())); } catch (Exception ignore) {}
            }
        }
        projectService.setServerProjects(serverId, projectIds);
        return Result.success("服务器所属项目已保存", null);
    }
}