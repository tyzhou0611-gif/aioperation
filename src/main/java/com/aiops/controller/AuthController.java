package com.aiops.controller;

import com.aiops.common.Result;
import com.aiops.controller.vo.LoginVO;
import com.aiops.controller.vo.UserBasicVO;
import com.aiops.controller.vo.UserInfoVO;
import com.aiops.entity.SysUser;
import com.aiops.service.SysMenuService;
import com.aiops.service.SysUserService;
import javax.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;

/**
 * 认证控制器
 *
 * 接口列表：
 * - POST /api/auth/login  登录，返回Token + 菜单权限
 * - GET  /api/auth/info   获取当前用户信息 + 动态菜单路由
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final SysUserService userService;
    private final SysMenuService menuService;

    /**
     * 用户登录
     * POST /api/auth/login
     *
     * @param request 登录请求体（username, password）
     * @return JWT Token + 用户基本信息 + 菜单树 + 权限列表
     */
    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginRequest request) {
        // 执行登录验证，获取token
        String token = userService.login(request.getUsername(), request.getPassword());

        // 获取用户信息
        SysUser user = userService.getUserByUsername(request.getUsername());

        // 获取菜单树
        List<SysMenuService.MenuTreeNode> menus = menuService.getMenuTreeByUsername(request.getUsername());

        // 获取权限列表
        Set<String> permissions = userService.getPermissions(request.getUsername());

        // 构建用户基本信息
        UserBasicVO userBasic = UserBasicVO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .email(user.getEmail())
                .avatar(user.getAvatar())
                .status(user.getStatus())
                .createTime(user.getCreateTime() != null
                        ? user.getCreateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                        : null)
                .build();

        // 构建响应
        LoginVO loginVO = LoginVO.builder()
                .token(token)
                .user(userBasic)
                .menus(menus)
                .permissions(permissions)
                .build();

        return Result.success("登录成功", loginVO);
    }

    /**
     * 获取当前用户信息
     * GET /api/auth/info
     *
     * @param authentication Spring Security 认证对象，注入当前登录用户
     * @return 用户详细信息 + 角色 + 权限 + 动态菜单路由
     */
    @GetMapping("/info")
    public Result<UserInfoVO> info(Authentication authentication) {
        String username = authentication.getName();

        // 获取用户信息
        SysUser user = userService.getUserByUsername(username);

        // 获取角色名称
        Set<String> roles = userService.getRoleNames(username);

        // 获取权限列表
        Set<String> permissions = userService.getPermissions(username);

        // 获取动态菜单树
        List<SysMenuService.MenuTreeNode> menus = menuService.getMenuTreeByUsername(username);

        // 构建用户基本信息
        UserBasicVO userBasic = UserBasicVO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .email(user.getEmail())
                .avatar(user.getAvatar())
                .status(user.getStatus())
                .createTime(user.getCreateTime() != null
                        ? user.getCreateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                        : null)
                .build();

        // 构建响应
        UserInfoVO userInfo = UserInfoVO.builder()
                .user(userBasic)
                .roles(roles)
                .permissions(permissions)
                .menus(menus)
                .build();

        return Result.success(userInfo);
    }
}