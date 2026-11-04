package com.aiops.service;

import com.aiops.common.ServiceException;
import com.aiops.entity.ProjectAsset;
import com.aiops.entity.ProjectRole;
import com.aiops.entity.SysMenu;
import com.aiops.entity.SysRole;
import com.aiops.entity.SysUser;
import com.aiops.repository.ProjectAssetRepository;
import com.aiops.repository.ProjectRoleRepository;
import com.aiops.repository.SysUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 项目级权限：
 * - ROLE_ADMIN / admin：可见全部项目与服务器
 * - 其他角色：只能看到 project_role 中授权给其角色的项目，以及这些项目关联的服务器
 */
@Service
@RequiredArgsConstructor
public class AccessControlService {

    private final SysUserRepository userRepository;
    private final ProjectRoleRepository projectRoleRepository;
    private final ProjectAssetRepository projectAssetRepository;

    public boolean isAdmin(String username) {
        if (username == null) return false;
        if ("admin".equalsIgnoreCase(username.trim())) return true;
        SysUser user = userRepository.findByUsername(username).orElse(null);
        if (user == null || user.getRoles() == null) return false;
        for (SysRole r : user.getRoles()) {
            if ("ROLE_ADMIN".equalsIgnoreCase(r.getName())
                    || "ROLE_SUPER_ADMIN".equalsIgnoreCase(r.getName())) return true;
        }
        return false;
    }

    /** 超级管理员：内置 admin / ROLE_SUPER_ADMIN（兼容旧 ROLE_ADMIN） */
    public boolean isSuperAdmin(String username) {
        return isAdmin(username);
    }

    /**
     * 是否拥有某个页面/功能权限。超级管理员拥有全部权限。
     * 权限来自角色绑定的 sys_menu.permission（页面和按钮都算）。
     */
    public boolean hasPermission(String username, String permission) {
        if (permission == null || permission.trim().isEmpty()) return true;
        if (isSuperAdmin(username)) return true;
        SysUser user = userRepository.findByUsername(username).orElse(null);
        if (user == null || user.getRoles() == null) return false;
        for (SysRole r : user.getRoles()) {
            if (r.getMenus() == null) continue;
            for (SysMenu m : r.getMenus()) {
                if (permission.equals(m.getPermission())) return true;
            }
        }
        return false;
    }

    public void assertPermission(String username, String permission) {
        if (!hasPermission(username, permission)) {
            throw new ServiceException(403, "权限不足：需要权限 " + permission);
        }
    }

    /** null 表示不限制（管理员）；空集合表示没有任何可见项目 */
    public Set<Long> accessibleProjectIds(String username) {
        if (isAdmin(username)) return null;
        Set<Long> projectIds = new HashSet<Long>();
        SysUser user = userRepository.findByUsername(username).orElse(null);
        if (user == null || user.getRoles() == null || user.getRoles().isEmpty()) return projectIds;
        List<Long> roleIds = new ArrayList<Long>();
        for (SysRole r : user.getRoles()) roleIds.add(r.getId());
        for (ProjectRole pr : projectRoleRepository.findByRoleIdIn(roleIds)) {
            if (pr.getProjectId() != null) projectIds.add(pr.getProjectId());
        }
        return projectIds;
    }

    /** null 表示不限制（管理员） */
    public Set<Long> accessibleServerIds(String username) {
        Set<Long> projectIds = accessibleProjectIds(username);
        if (projectIds == null) return null;
        Set<Long> serverIds = new HashSet<Long>();
        if (projectIds.isEmpty()) return serverIds;
        for (ProjectAsset pa : projectAssetRepository.findByProjectIdInAndAssetType(projectIds, "SERVER")) {
            if (pa.getAssetId() != null) serverIds.add(pa.getAssetId());
        }
        return serverIds;
    }

    public void assertServerAccess(String username, Long serverId) {
        Set<Long> ids = accessibleServerIds(username);
        if (ids != null && (serverId == null || !ids.contains(serverId))) {
            throw new ServiceException(403, "无权访问该服务器（不在当前角色的项目范围内）");
        }
    }

    public void assertAdmin(String username) {
        if (!isAdmin(username)) {
            throw new ServiceException(403, "该操作需要管理员权限");
        }
    }
}