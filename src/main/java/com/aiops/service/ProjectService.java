package com.aiops.service;

import com.aiops.common.ServiceException;
import com.aiops.controller.vo.ProjectVO;
import com.aiops.entity.Project;
import com.aiops.entity.ProjectAsset;
import com.aiops.entity.ProjectRole;
import com.aiops.entity.SysRole;
import com.aiops.repository.AppAssetRepository;
import com.aiops.repository.MiddlewareAssetRepository;
import com.aiops.repository.ProjectAssetRepository;
import com.aiops.repository.ProjectRepository;
import com.aiops.repository.ProjectRoleRepository;
import com.aiops.repository.ServerAssetRepository;
import com.aiops.repository.SysRoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 项目管理：项目 CRUD + 项目与资产（服务器/中间件/应用）的多对多关联。
 */
@Service
@RequiredArgsConstructor
public class ProjectService {

    public static final String TYPE_SERVER = "SERVER";
    public static final String TYPE_MIDDLEWARE = "MIDDLEWARE";
    public static final String TYPE_APP = "APP";

    private final ProjectRepository projectRepository;
    private final ProjectAssetRepository projectAssetRepository;
    private final ProjectRoleRepository projectRoleRepository;
    private final SysRoleRepository roleRepository;
    private final ServerAssetRepository serverRepository;
    private final MiddlewareAssetRepository middlewareRepository;
    private final AppAssetRepository appRepository;
    private final AccessControlService accessControl;

    /** 项目列表（含资产数量），非管理员只返回其角色可见项目 */
    public List<ProjectVO> list(String keyword, String username) {
        List<Project> projects;
        if (keyword == null || keyword.trim().isEmpty()) {
            projects = projectRepository.findAllByOrderByIdAsc();
        } else {
            String k = keyword.trim();
            projects = projectRepository
                    .findByNameContainingIgnoreCaseOrCodeContainingIgnoreCaseOrderByIdAsc(k, k);
        }
        Set<Long> allowed = accessControl.accessibleProjectIds(username);
        return projects.stream()
                .filter(p -> allowed == null || allowed.contains(p.getId()))
                .map(p -> toVO(p, projectAssetRepository.findByProjectId(p.getId())))
                .collect(Collectors.toList());
    }

    /** 大屏项目下拉选项（仅启用项目，非管理员只返回授权项目） */
    public List<Map<String, Object>> options(String username) {
        Set<Long> allowed = accessControl.accessibleProjectIds(username);
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Project p : projectRepository.findAllByOrderByIdAsc()) {
            if (!"ACTIVE".equalsIgnoreCase(p.getStatus())) continue;
            if (allowed != null && !allowed.contains(p.getId())) continue;
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("id", p.getId());
            m.put("name", p.getName());
            m.put("code", p.getCode());
            result.add(m);
        }
        return result;
    }

    public ProjectVO detail(Long id, String username) {
        Set<Long> allowed = accessControl.accessibleProjectIds(username);
        if (allowed != null && !allowed.contains(id)) {
            throw new ServiceException(403, "无权访问该项目");
        }
        Project p = getById(id);
        return toVO(p, projectAssetRepository.findByProjectId(id));
    }

    public Project create(Project input) {
        normalizeAndValidate(input, null);
        input.setId(null);
        return projectRepository.save(input);
    }

    public Project update(Long id, Project input) {
        Project existing = getById(id);
        normalizeAndValidate(input, id);
        existing.setName(input.getName());
        existing.setCode(input.getCode());
        existing.setOwner(input.getOwner());
        existing.setDescription(input.getDescription());
        existing.setStatus(input.getStatus());
        return projectRepository.save(existing);
    }

    @Transactional
    public void delete(Long id) {
        getById(id);
        projectAssetRepository.deleteByProjectId(id);
        projectRoleRepository.deleteByProjectId(id);
        projectRepository.deleteById(id);
    }

    /** 项目已关联的资产 ID */
    public Map<String, List<Long>> assets(Long projectId) {
        getById(projectId);
        return groupAssets(projectAssetRepository.findByProjectId(projectId));
    }

    /** 全量替换项目资产关联 */
    @Transactional
    public void replaceAssets(Long projectId, List<Long> serverIds, List<Long> middlewareIds, List<Long> appIds) {
        getById(projectId);
        projectAssetRepository.deleteByProjectId(projectId);

        Set<String> seen = new HashSet<String>();
        saveAssets(projectId, TYPE_SERVER, serverIds, seen);
        saveAssets(projectId, TYPE_MIDDLEWARE, middlewareIds, seen);
        saveAssets(projectId, TYPE_APP, appIds, seen);
    }

    public Project getById(Long id) {
        return projectRepository.findById(id)
                .orElseThrow(() -> new ServiceException("项目不存在: " + id));
    }

    // ==================== 内部方法 ====================

    private void normalizeAndValidate(Project input, Long selfId) {
        if (input.getName() == null || input.getName().trim().isEmpty()) {
            throw new ServiceException("项目名称不能为空");
        }
        if (input.getCode() == null || input.getCode().trim().isEmpty()) {
            throw new ServiceException("项目编码不能为空");
        }
        input.setName(input.getName().trim());
        input.setCode(input.getCode().trim());

        Optional<Project> sameCode = projectRepository.findByCode(input.getCode());
        if (sameCode.isPresent() && (selfId == null || !sameCode.get().getId().equals(selfId))) {
            throw new ServiceException("项目编码已存在: " + input.getCode());
        }
        if (input.getStatus() == null || input.getStatus().trim().isEmpty()) {
            input.setStatus("ACTIVE");
        }
    }

    private void saveAssets(Long projectId, String type, List<Long> ids, Set<String> seen) {
        if (ids == null) return;
        for (Long id : ids) {
            if (id == null || !seen.add(type + ":" + id)) continue;
            if (!assetExists(type, id)) continue;
            projectAssetRepository.save(new ProjectAsset(projectId, type, id));
        }
    }

    private boolean assetExists(String type, Long id) {
        if (TYPE_SERVER.equals(type)) return serverRepository.existsById(id);
        if (TYPE_MIDDLEWARE.equals(type)) return middlewareRepository.existsById(id);
        if (TYPE_APP.equals(type)) return appRepository.existsById(id);
        return false;
    }

    private Map<String, List<Long>> groupAssets(List<ProjectAsset> assets) {
        Map<String, List<Long>> result = new LinkedHashMap<String, List<Long>>();
        result.put("serverIds", new ArrayList<Long>());
        result.put("middlewareIds", new ArrayList<Long>());
        result.put("appIds", new ArrayList<Long>());
        for (ProjectAsset a : assets) {
            if (TYPE_SERVER.equals(a.getAssetType())) result.get("serverIds").add(a.getAssetId());
            else if (TYPE_MIDDLEWARE.equals(a.getAssetType())) result.get("middlewareIds").add(a.getAssetId());
            else if (TYPE_APP.equals(a.getAssetType())) result.get("appIds").add(a.getAssetId());
        }
        return result;
    }

    private ProjectVO toVO(Project p, List<ProjectAsset> assets) {
        ProjectVO vo = new ProjectVO();
        vo.setId(p.getId());
        vo.setName(p.getName());
        vo.setCode(p.getCode());
        vo.setOwner(p.getOwner());
        vo.setDescription(p.getDescription());
        vo.setStatus(p.getStatus());
        vo.setCreateTime(p.getCreateTime());
        vo.setUpdateTime(p.getUpdateTime());
        for (ProjectAsset a : assets) {
            if (TYPE_SERVER.equals(a.getAssetType())) vo.setServerCount(vo.getServerCount() + 1);
            else if (TYPE_MIDDLEWARE.equals(a.getAssetType())) vo.setMiddlewareCount(vo.getMiddlewareCount() + 1);
            else if (TYPE_APP.equals(a.getAssetType())) vo.setAppCount(vo.getAppCount() + 1);
        }
        List<Long> roleIds = new ArrayList<Long>();
        List<String> roleNames = new ArrayList<String>();
        for (ProjectRole pr : projectRoleRepository.findByProjectId(p.getId())) {
            roleIds.add(pr.getRoleId());
            SysRole role = roleRepository.findById(pr.getRoleId()).orElse(null);
            roleNames.add(role == null ? ("角色#" + pr.getRoleId()) : role.getName());
        }
        vo.setRoleIds(roleIds);
        vo.setRoleNames(roleNames);
        return vo;
    }

    /** 设置项目的可见角色（管理员） */
    @Transactional
    public void setRoles(Long projectId, List<Long> roleIds) {
        getById(projectId);
        projectRoleRepository.deleteByProjectId(projectId);
        if (roleIds == null) return;
        Set<Long> seen = new HashSet<Long>();
        for (Long roleId : roleIds) {
            if (roleId == null || !seen.add(roleId) || !roleRepository.existsById(roleId)) continue;
            projectRoleRepository.save(new ProjectRole(projectId, roleId));
        }
    }

    /** 设置服务器所属项目（服务器管理页使用） */
    @Transactional
    public void setServerProjects(Long serverId, List<Long> projectIds) {
        if (!serverRepository.existsById(serverId)) throw new ServiceException("服务器不存在: " + serverId);
        projectAssetRepository.deleteByAssetTypeAndAssetId(TYPE_SERVER, serverId);
        if (projectIds == null) return;
        Set<Long> seen = new HashSet<Long>();
        for (Long projectId : projectIds) {
            if (projectId == null || !seen.add(projectId) || !projectRepository.existsById(projectId)) continue;
            projectAssetRepository.save(new ProjectAsset(projectId, TYPE_SERVER, serverId));
        }
    }
}