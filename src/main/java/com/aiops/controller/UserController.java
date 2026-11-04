package com.aiops.controller;

import java.util.stream.Collectors;
import com.aiops.common.PageResult;
import com.aiops.common.Result;
import com.aiops.common.ServiceException;
import com.aiops.entity.SysRole;
import com.aiops.entity.SysUser;
import com.aiops.repository.SysRoleRepository;
import com.aiops.repository.SysUserRepository;
import com.aiops.service.AccessControlService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户管理。
 *
 * 角色分配是超级管理员专属能力：
 * - 其他角色（即使拥有用户管理权限）只能创建/编辑用户资料，不能勾选或修改角色
 * - 后端强制校验，不能通过直接调接口绕过
 */
@RestController
@RequestMapping("/api/user")
public class UserController {

    private final SysUserRepository userRepository;
    private final SysRoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final AccessControlService accessControl;

    public UserController(SysUserRepository userRepository, SysRoleRepository roleRepository,
                          PasswordEncoder passwordEncoder, AccessControlService accessControl) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.accessControl = accessControl;
    }

    private String user(Authentication auth) { return auth == null ? "anonymous" : auth.getName(); }

    private void require(Authentication auth, String permission) {
        accessControl.assertPermission(user(auth), permission);
    }

    private boolean isSuper(Authentication auth) {
        return accessControl.isSuperAdmin(user(auth));
    }

    @GetMapping("/page")
    public Result<PageResult<Map<String, Object>>> page(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            Authentication auth) {
        require(auth, "sys:user:list");
        org.springframework.data.jpa.domain.Specification<SysUser> spec = (root, q, cb) -> {
            if (keyword == null || keyword.trim().isEmpty()) return cb.conjunction();
            String p = "%" + keyword.trim() + "%";
            return cb.or(cb.like(root.get("username"), p), cb.like(root.get("nickname"), p), cb.like(root.get("email"), p));
        };
        org.springframework.data.domain.Page<SysUser> result = userRepository.findAll(spec,
                PageRequest.of(page - 1, pageSize, Sort.by(Sort.Direction.ASC, "id")));
        List<Map<String, Object>> records = result.getContent().stream().map(this::toSafe).collect(Collectors.toList());
        PageResult<Map<String, Object>> pr = new PageResult<>();
        pr.setRecords(records);
        pr.setTotal(result.getTotalElements());
        pr.setPage(page);
        pr.setPageSize(pageSize);
        pr.setPages(result.getTotalPages());
        return Result.success(pr);
    }

    @PostMapping
    public Result<Map<String, Object>> create(@RequestBody SysUser user, Authentication auth) {
        require(auth, "sys:user:add");
        if (user.getUsername() == null || user.getUsername().trim().isEmpty()) {
            throw new ServiceException("用户名不能为空");
        }
        user.setUsername(user.getUsername().trim());
        if (userRepository.existsByUsername(user.getUsername())) {
            throw new ServiceException("用户名已存在: " + user.getUsername());
        }
        List<Long> roleIds = user.getRoleIds();
        if (roleIds != null && !roleIds.isEmpty() && !isSuper(auth)) {
            throw new ServiceException(403, "仅超级管理员可以给用户分配角色");
        }
        user.setId(null);
        user.setPassword(passwordEncoder.encode(
                user.getPassword() != null && !user.getPassword().trim().isEmpty() ? user.getPassword() : "123456"));
        if (user.getStatus() == null) user.setStatus(0);
        if (isSuper(auth) && roleIds != null) user.setRoles(resolveRoles(roleIds));
        else user.setRoles(new java.util.HashSet<SysRole>());
        SysUser saved = userRepository.save(user);
        return Result.success("新增成功", toSafe(saved));
    }

    @PutMapping("/{id}")
    public Result<Map<String, Object>> update(@PathVariable Long id, @RequestBody SysUser update,
                                              Authentication auth) {
        require(auth, "sys:user:edit");
        SysUser existing = userRepository.findById(id)
                .orElseThrow(() -> new ServiceException("用户不存在: " + id));
        boolean superAdmin = isSuper(auth);
        boolean builtinAdmin = "admin".equalsIgnoreCase(existing.getUsername()) || id == 1L;
        if (builtinAdmin && !superAdmin) {
            throw new ServiceException(403, "内置超级管理员账户仅可由超级管理员维护");
        }
        if (update.getRoleIds() != null) {
            if (!superAdmin) throw new ServiceException(403, "仅超级管理员可以给用户分配角色");
            if (builtinAdmin) throw new ServiceException("内置超级管理员角色不可修改");
            existing.setRoles(resolveRoles(update.getRoleIds()));
        }
        if (update.getNickname() != null) existing.setNickname(update.getNickname());
        if (update.getEmail() != null) existing.setEmail(update.getEmail());
        if (update.getStatus() != null) existing.setStatus(update.getStatus());
        if (update.getPassword() != null && !update.getPassword().trim().isEmpty()) {
            existing.setPassword(passwordEncoder.encode(update.getPassword()));
        }
        SysUser saved = userRepository.save(existing);
        return Result.success("更新成功", toSafe(saved));
    }

    /** 分配角色：仅超级管理员 */
    @PutMapping("/{id}/roles")
    public Result<Map<String, Object>> assignRoles(@PathVariable Long id,
                                                   @RequestBody Map<String, Object> body,
                                                   Authentication auth) {
        if (!isSuper(auth)) {
            throw new ServiceException(403, "仅超级管理员可以给用户分配角色");
        }
        SysUser existing = userRepository.findById(id)
                .orElseThrow(() -> new ServiceException("用户不存在: " + id));
        if ("admin".equalsIgnoreCase(existing.getUsername()) || id == 1L) {
            throw new ServiceException("内置超级管理员角色不可修改");
        }
        List<Long> ids = new ArrayList<Long>();
        Object raw = body.get("roleIds");
        if (raw instanceof List) {
            for (Object o : (List<Object>) raw) {
                try { ids.add(Long.valueOf(o.toString())); } catch (Exception ignore) { }
            }
        }
        existing.setRoles(resolveRoles(ids));
        SysUser saved = userRepository.save(existing);
        return Result.success("角色已分配", toSafe(saved));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id, Authentication auth) {
        require(auth, "sys:user:delete");
        SysUser existing = userRepository.findById(id)
                .orElseThrow(() -> new ServiceException("用户不存在: " + id));
        if (id == 1L || "admin".equalsIgnoreCase(existing.getUsername())) {
            throw new ServiceException("不能删除内置管理员账户");
        }
        userRepository.deleteById(id);
        return Result.success("删除成功", null);
    }

    private java.util.Set<SysRole> resolveRoles(List<Long> ids) {
        java.util.Set<SysRole> roles = new java.util.HashSet<SysRole>();
        if (ids == null || ids.isEmpty()) return roles;
        for (SysRole r : roleRepository.findAllById(ids)) roles.add(r);
        return roles;
    }

    private Map<String, Object> toSafe(SysUser u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("username", u.getUsername());
        m.put("nickname", u.getNickname());
        m.put("email", u.getEmail());
        m.put("status", u.getStatus());
        m.put("createTime", u.getCreateTime());
        List<String> names = new ArrayList<String>();
        List<Long> ids = new ArrayList<Long>();
        List<Map<String, Object>> details = new ArrayList<Map<String, Object>>();
        for (SysRole r : u.getRoles()) {
            names.add(r.getName());
            ids.add(r.getId());
            Map<String, Object> d = new LinkedHashMap<String, Object>();
            d.put("id", r.getId());
            d.put("name", r.getName());
            d.put("label", r.getLabel() == null || r.getLabel().isEmpty() ? r.getName() : r.getLabel());
            d.put("tagColor", r.getTagColor());
            d.put("scope", r.getScope());
            details.add(d);
        }
        m.put("roles", names);
        m.put("roleIds", ids);
        m.put("roleDetails", details);
        return m;
    }
}