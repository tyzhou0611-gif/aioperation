package com.aiops.controller;

import com.aiops.common.Result;
import com.aiops.common.ServiceException;
import com.aiops.entity.SysMenu;
import com.aiops.entity.SysRole;
import com.aiops.entity.SysUser;
import com.aiops.repository.SysMenuRepository;
import com.aiops.repository.SysRoleRepository;
import com.aiops.repository.SysUserRepository;
import com.aiops.service.AccessControlService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 角色与权限管理。
 *
 * - 角色列表：拥有 sys:role:list 的角色可查看（安全管理员/审计员可只读查看）
 * - 新增/编辑/删除/配置权限：仅超级管理员
 * - 自定义角色：超级管理员可创建并勾选页面/功能权限
 */
@RestController
@RequestMapping("/api/role")
@RequiredArgsConstructor
public class RoleController {

    private final SysRoleRepository roleRepo;
    private final SysMenuRepository menuRepo;
    private final SysUserRepository userRepo;
    private final AccessControlService accessControl;

    private static final List<String> ROLE_ORDER = Arrays.asList(
            "ROLE_SUPER_ADMIN", "ROLE_PLATFORM_OPS", "ROLE_MONITOR", "ROLE_CHANGE",
            "ROLE_DATA_OPS", "ROLE_INFRA", "ROLE_DBA", "ROLE_SECURITY",
            "ROLE_AUDITOR", "ROLE_PROJECT_ADMIN", "ROLE_SERVICE_DESK", "ROLE_VIEWER");

    private String user(Authentication auth) { return auth == null ? "anonymous" : auth.getName(); }

    @GetMapping("/list")
    public Result<List<Map<String, Object>>> list(Authentication auth) {
        String username = user(auth);
        if (!accessControl.isSuperAdmin(username)) {
            accessControl.assertPermission(username, "sys:role:list");
        }
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (SysRole r : roleRepo.findAll()) {
            out.add(toView(r, true));
        }
        sortViews(out);
        return Result.success(out);
    }

    /** 给用户管理页使用的精简角色选项（不返回权限明细） */
    @GetMapping("/options")
    public Result<List<Map<String, Object>>> options() {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (SysRole r : roleRepo.findAll()) {
            out.add(toView(r, false));
        }
        sortViews(out);
        return Result.success(out);
    }

    /** 权限树原始数据（仅超级管理员配置角色时使用） */
    @GetMapping("/menu-tree")
    public Result<List<Map<String, Object>>> menuTree(Authentication auth) {
        assertSuper(auth);
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (SysMenu m : menuRepo.findAllByOrderBySortAsc()) {
            Map<String, Object> n = new LinkedHashMap<String, Object>();
            n.put("id", m.getId());
            n.put("parentId", m.getParentId());
            n.put("name", m.getName());
            n.put("path", m.getPath());
            n.put("permission", m.getPermission());
            n.put("type", m.getType());
            n.put("sort", m.getSort());
            out.add(n);
        }
        return Result.success(out);
    }

    @PostMapping
    public Result<Map<String, Object>> create(@RequestBody Map<String, Object> body, Authentication auth) {
        assertSuper(auth);
        String code = str(body.get("name")).trim().toUpperCase();
        String label = str(body.get("label")).trim();
        if (code.isEmpty() || !code.matches("^ROLE_[A-Z0-9_]{2,40}$")) {
            throw new ServiceException("角色标识格式应为 ROLE_XXX（大写字母/数字/下划线）");
        }
        if (label.isEmpty()) label = code;
        if (roleRepo.findByName(code).isPresent()) {
            throw new ServiceException("角色标识已存在: " + code);
        }
        SysRole role = new SysRole();
        role.setName(code);
        role.setLabel(label);
        role.setDescription(str(body.get("description")));
        role.setTagColor(defaultColor(str(body.get("tagColor"))));
        role.setScope(defaultScope(str(body.get("scope"))));
        role.setBuiltin(0);
        role.setMenus(resolveMenus(body.get("menuIds")));
        SysRole saved = roleRepo.save(role);
        return Result.success("角色已创建", toView(saved, true));
    }

    @PutMapping("/{id}")
    public Result<Map<String, Object>> update(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                              Authentication auth) {
        assertSuper(auth);
        SysRole role = roleRepo.findById(id).orElseThrow(() -> new ServiceException("角色不存在: " + id));
        if (!"ROLE_SUPER_ADMIN".equals(role.getName())) {
            if (body.containsKey("label")) role.setLabel(str(body.get("label")));
            if (body.containsKey("description")) role.setDescription(str(body.get("description")));
            if (body.containsKey("tagColor")) role.setTagColor(defaultColor(str(body.get("tagColor"))));
            if (body.containsKey("scope")) role.setScope(defaultScope(str(body.get("scope"))));
        }
        // 超级管理员角色始终拥有全部权限，不允许在这里被清空
        if (!"ROLE_SUPER_ADMIN".equals(role.getName())) {
            if (body.containsKey("menuIds")) role.setMenus(resolveMenus(body.get("menuIds")));
        }
        SysRole saved = roleRepo.save(role);
        return Result.success("角色已更新", toView(saved, true));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id, Authentication auth) {
        assertSuper(auth);
        SysRole role = roleRepo.findById(id).orElseThrow(() -> new ServiceException("角色不存在: " + id));
        if (role.getBuiltin() != null && role.getBuiltin() == 1) {
            throw new ServiceException("内置角色不可删除");
        }
        for (SysUser u : userRepo.findAll()) {
            if (u.getRoles() != null && u.getRoles().stream().anyMatch(r -> r.getId().equals(role.getId()))) {
                throw new ServiceException("该角色仍被用户 " + u.getUsername() + " 使用，不能删除");
            }
        }
        roleRepo.delete(role);
        return Result.success("角色已删除", null);
    }

    // ==================== 内部 ====================

    private void assertSuper(Authentication auth) {
        if (!accessControl.isSuperAdmin(user(auth))) {
            throw new ServiceException(403, "仅超级管理员可以管理角色和权限");
        }
    }

    private Map<String, Object> toView(SysRole r, boolean withMenus) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("id", r.getId());
        m.put("name", r.getName());
        m.put("label", r.getLabel() == null || r.getLabel().isEmpty() ? r.getName() : r.getLabel());
        m.put("description", r.getDescription());
        m.put("tagColor", r.getTagColor() == null || r.getTagColor().isEmpty() ? "#7c3aed" : r.getTagColor());
        m.put("builtin", r.getBuiltin() == null ? 0 : r.getBuiltin());
        m.put("scope", r.getScope() == null ? "GLOBAL" : r.getScope());
        m.put("createTime", r.getCreateTime());
        m.put("updateTime", r.getUpdateTime());
        if (withMenus) {
            List<Long> ids = new ArrayList<Long>();
            int perms = 0;
            for (SysMenu menu : r.getMenus()) {
                ids.add(menu.getId());
                if (menu.getPermission() != null && !menu.getPermission().isEmpty()) perms++;
            }
            m.put("menuIds", ids);
            m.put("permissionCount", perms);
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    private Set<SysMenu> resolveMenus(Object raw) {
        Set<SysMenu> menus = new HashSet<SysMenu>();
        if (!(raw instanceof List)) return menus;
        List<Long> ids = new ArrayList<Long>();
        for (Object o : (List<Object>) raw) {
            try { ids.add(Long.valueOf(o.toString())); } catch (Exception ignore) { }
        }
        if (ids.isEmpty()) return menus;
        for (SysMenu m : menuRepo.findAllById(ids)) menus.add(m);
        return menus;
    }

    private String defaultColor(String color) {
        String c = color == null ? "" : color.trim();
        return c.matches("^#[0-9a-fA-F]{6}$") ? c : "#7c3aed";
    }

    private String defaultScope(String scope) {
        String s = scope == null ? "" : scope.trim().toUpperCase();
        if ("GLOBAL".equals(s) || "TENANT".equals(s) || "PROJECT".equals(s) || "READONLY".equals(s)) return s;
        return "GLOBAL";
    }

    private String str(Object o) { return o == null ? "" : o.toString(); }

    private long num(Object o) { return o instanceof Number ? ((Number) o).longValue() : 0L; }

    /** 内置角色按职责顺序展示，自定义角色排在最后（按 id） */
    private void sortViews(List<Map<String, Object>> list) {
        list.sort(Comparator
                .comparingInt((Map<String, Object> m) -> {
                    int i = ROLE_ORDER.indexOf(String.valueOf(m.get("name")));
                    return i < 0 ? 100 : i;
                })
                .thenComparingLong(m -> num(m.get("id"))));
    }
}