package com.aiops.service;

import com.aiops.common.ServiceException;
import com.aiops.entity.SysMenu;
import com.aiops.entity.SysRole;
import com.aiops.entity.SysUser;
import com.aiops.repository.SysMenuRepository;
import com.aiops.repository.SysUserRepository;
import com.aiops.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 系统用户服务
 * 负责用户登录验证、用户信息获取
 */
@Service
@RequiredArgsConstructor
public class SysUserService {

    private final SysUserRepository userRepository;
    private final SysMenuRepository menuRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    /**
     * 用户登录
     *
     * @param username 用户名
     * @param password 密码（明文）
     * @return JWT token
     */
    public String login(String username, String password) {
        SysUser user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ServiceException(401, "用户名或密码错误"));

        if (user.getStatus() != null && user.getStatus() == 1) {
            throw new ServiceException(403, "账户已被停用，请联系管理员");
        }

        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new ServiceException(401, "用户名或密码错误");
        }

        return jwtUtil.generateToken(user.getUsername());
    }

    /**
     * 根据用户名获取用户信息（含角色列表）
     */
    public SysUser getUserByUsername(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new ServiceException(401, "用户不存在"));
    }

    /**
     * 获取用户的角色名称列表
     */
    public Set<String> getRoleNames(String username) {
        SysUser user = getUserByUsername(username);
        return user.getRoles().stream()
                .map(SysRole::getName)
                .collect(Collectors.toSet());
    }

    /**
     * 获取用户的权限标识列表
     * 通过角色关联的菜单获取所有 permission 字段
     */
    public Set<String> getPermissions(String username) {
        SysUser user = getUserByUsername(username);
        boolean superAdmin = "admin".equalsIgnoreCase(username)
                || user.getRoles().stream().anyMatch(r -> "ROLE_SUPER_ADMIN".equalsIgnoreCase(r.getName())
                        || "ROLE_ADMIN".equalsIgnoreCase(r.getName()));
        if (superAdmin) {
            Set<String> all = new HashSet<String>();
            for (SysMenu m : menuRepository.findAll()) {
                if (m.getPermission() != null && !m.getPermission().trim().isEmpty()) all.add(m.getPermission().trim());
            }
            // 前端快捷判断：超级管理员拥有全部权限
            all.add("*");
            return all;
        }
        return user.getRoles().stream()
                .flatMap(role -> role.getMenus().stream())
                .map(menu -> menu.getPermission())
                .filter(p -> p != null && !p.isEmpty())
                .collect(Collectors.toSet());
    }
}