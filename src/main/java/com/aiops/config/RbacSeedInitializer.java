package com.aiops.config;

import com.aiops.entity.SysMenu;
import com.aiops.entity.SysRole;
import com.aiops.entity.SysUser;
import com.aiops.repository.SysMenuRepository;
import com.aiops.repository.SysRoleRepository;
import com.aiops.repository.SysUserRepository;
import com.aiops.service.ConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RBAC 初始化：
 * - 把旧的 ROLE_ADMIN / ROLE_USER 平滑迁移为 ROLE_SUPER_ADMIN / ROLE_VIEWER
 * - 初始化 12 个内置角色（标签颜色、数据范围、职责描述）
 * - 初始化页面/功能权限（sys_menu type=2），并按职责给内置角色分配菜单
 * - 保证 admin 用户始终拥有超级管理员角色
 *
 * 只在首次升级时完整执行一次；之后超级管理员在角色管理页面调整权限不会被重启覆盖。
 */
@Component
public class RbacSeedInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RbacSeedInitializer.class);
    private static final String SEED_VERSION = "2026-10-27-rbac-v1";

    private final SysRoleRepository roleRepo;
    private final SysMenuRepository menuRepo;
    private final SysUserRepository userRepo;
    private final ConfigService config;

    public RbacSeedInitializer(SysRoleRepository roleRepo, SysMenuRepository menuRepo,
                               SysUserRepository userRepo, ConfigService config) {
        this.roleRepo = roleRepo;
        this.menuRepo = menuRepo;
        this.userRepo = userRepo;
        this.config = config;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        try {
            migrateLegacyRoleNames();
            if (!SEED_VERSION.equals(config.getString("rbac.seed.version", ""))) {
                seedButtonMenus();
                seedBuiltinRoles();
                config.setString("rbac.seed.version", SEED_VERSION);
                log.info("RBAC 初始化完成：12 个内置角色与页面/功能权限已就绪");
            }
            ensureAdminSuperRole();
        } catch (Exception e) {
            log.error("RBAC 初始化失败（不影响登录，可在角色管理中手工配置）", e);
        }
    }

    private void migrateLegacyRoleNames() {
        SysRole superRole = roleRepo.findByName("ROLE_SUPER_ADMIN").orElse(null);
        SysRole adminRole = roleRepo.findByName("ROLE_ADMIN").orElse(null);
        if (superRole == null && adminRole != null) {
            adminRole.setName("ROLE_SUPER_ADMIN");
            roleRepo.save(adminRole);
            log.info("RBAC 迁移：ROLE_ADMIN -> ROLE_SUPER_ADMIN");
        }
        SysRole viewer = roleRepo.findByName("ROLE_VIEWER").orElse(null);
        SysRole userRole = roleRepo.findByName("ROLE_USER").orElse(null);
        if (viewer == null && userRole != null) {
            userRole.setName("ROLE_VIEWER");
            roleRepo.save(userRole);
            log.info("RBAC 迁移：ROLE_USER -> ROLE_VIEWER");
        }
    }

    private void ensureAdminSuperRole() {
        SysRole superRole = roleRepo.findByName("ROLE_SUPER_ADMIN").orElse(null);
        if (superRole == null) {
            superRole = roleRepo.save(SysRole.builder()
                    .name("ROLE_SUPER_ADMIN").label("超级管理员").tagColor("#ef4444")
                    .scope("GLOBAL").builtin(1)
                    .description("系统初始化、角色权限模型、全局配置、紧急兜底；不参与日常审批；操作强审计；建议双人复核")
                    .menus(new HashSet<SysMenu>(menuRepo.findAll()))
                    .build());
        }
        SysUser admin = userRepo.findByUsername("admin").orElse(null);
        if (admin != null) {
            boolean has = admin.getRoles().stream().anyMatch(r -> "ROLE_SUPER_ADMIN".equals(r.getName()));
            if (!has) {
                admin.getRoles().add(superRole);
                userRepo.save(admin);
                log.info("RBAC：已确保 admin 用户拥有 ROLE_SUPER_ADMIN");
            }
        }
    }

    private void seedButtonMenus() {
        List<SysMenu> all = menuRepo.findAll();
        Map<String, SysMenu> byPermission = new LinkedHashMap<String, SysMenu>();
        Map<String, SysMenu> byPath = new LinkedHashMap<String, SysMenu>();
        for (SysMenu m : all) {
            if (m.getPermission() != null && !m.getPermission().trim().isEmpty()) byPermission.put(m.getPermission().trim(), m);
            if (m.getPath() != null && !m.getPath().trim().isEmpty()) byPath.put(m.getPath().trim(), m);
        }
        for (ButtonDef b : BUTTONS) {
            if (byPermission.containsKey(b.permission)) continue;
            SysMenu parent = byPath.get(b.parentPath);
            if (parent == null) {
                log.warn("RBAC 跳过按钮权限 {}：父菜单 {} 不存在", b.permission, b.parentPath);
                continue;
            }
            SysMenu menu = new SysMenu();
            menu.setParentId(parent.getId());
            menu.setName(b.name);
            menu.setPermission(b.permission);
            menu.setType(2);
            menu.setSort(b.sort);
            menu.setPath("");
            menu.setComponent("");
            menu.setIcon("");
            menu = menuRepo.save(menu);
            byPermission.put(b.permission, menu);
        }
    }

    private void seedBuiltinRoles() {
        List<SysMenu> all = menuRepo.findAll();
        Map<String, SysMenu> byPermission = new LinkedHashMap<String, SysMenu>();
        Map<String, SysMenu> byPath = new LinkedHashMap<String, SysMenu>();
        Map<Long, SysMenu> byId = new LinkedHashMap<Long, SysMenu>();
        for (SysMenu m : all) {
            byId.put(m.getId(), m);
            if (m.getPermission() != null && !m.getPermission().trim().isEmpty()) byPermission.put(m.getPermission().trim(), m);
            if (m.getPath() != null && !m.getPath().trim().isEmpty()) byPath.put(m.getPath().trim(), m);
        }
        for (RoleDef def : ROLES) {
            SysRole role = roleRepo.findByName(def.code).orElseGet(() -> {
                SysRole r = new SysRole();
                r.setName(def.code);
                return r;
            });
            role.setLabel(def.label);
            role.setDescription(def.description);
            role.setTagColor(def.color);
            role.setScope(def.scope);
            role.setBuiltin(1);

            Set<SysMenu> menus = new HashSet<SysMenu>();
            if ("ROLE_SUPER_ADMIN".equals(def.code)) {
                menus.addAll(all);
            } else {
                for (String path : def.pages) {
                    SysMenu m = byPath.get(path);
                    if (m != null) addWithParents(menus, m, byId);
                }
                for (String p : def.buttons) {
                    SysMenu m = byPermission.get(p);
                    if (m != null) addWithParents(menus, m, byId);
                }
            }
            role.setMenus(menus);
            roleRepo.save(role);
        }
    }

    private void addWithParents(Set<SysMenu> out, SysMenu menu, Map<Long, SysMenu> byId) {
        if (menu == null) return;
        out.add(menu);
        Long pid = menu.getParentId();
        int guard = 0;
        while (pid != null && pid > 0 && guard++ < 10) {
            SysMenu parent = byId.get(pid);
            if (parent == null) break;
            out.add(parent);
            pid = parent.getParentId();
        }
    }

    // ==================== 定义 ====================

    private static final class ButtonDef {
        final String parentPath, name, permission;
        final int sort;
        ButtonDef(String parentPath, String name, String permission, int sort) {
            this.parentPath = parentPath; this.name = name; this.permission = permission; this.sort = sort;
        }
    }

    private static final class RoleDef {
        final String code, label, color, scope, description;
        final List<String> pages, buttons;
        RoleDef(String code, String label, String color, String scope, String description,
                List<String> pages, List<String> buttons) {
            this.code = code; this.label = label; this.color = color; this.scope = scope;
            this.description = description; this.pages = pages; this.buttons = buttons;
        }
    }

    private static final List<ButtonDef> BUTTONS = Arrays.asList(
            new ButtonDef("/system/user", "新增用户", "sys:user:add", 1),
            new ButtonDef("/system/user", "编辑用户", "sys:user:edit", 2),
            new ButtonDef("/system/user", "删除用户", "sys:user:delete", 3),
            new ButtonDef("/system/user", "分配角色", "sys:user:assign-role", 4),
            new ButtonDef("/system/role", "新增角色", "sys:role:add", 1),
            new ButtonDef("/system/role", "编辑角色", "sys:role:edit", 2),
            new ButtonDef("/system/role", "删除角色", "sys:role:delete", 3),
            new ButtonDef("/system/role", "配置权限", "sys:role:assign-permission", 4),
            new ButtonDef("/system/menu", "新增菜单", "sys:menu:add", 1),
            new ButtonDef("/system/menu", "编辑菜单", "sys:menu:edit", 2),
            new ButtonDef("/system/menu", "删除菜单", "sys:menu:delete", 3),
            new ButtonDef("/asset/server", "编辑服务器", "asset:server:edit", 2),
            new ButtonDef("/asset/server", "删除服务器", "asset:server:delete", 3),
            new ButtonDef("/asset/server", "探测服务器", "asset:server:probe", 4),
            new ButtonDef("/asset/server", "导入服务器", "asset:server:import", 5),
            new ButtonDef("/asset/server", "Prometheus 同步", "asset:server:sync", 6),
            new ButtonDef("/asset/middleware", "新增中间件", "asset:middleware:add", 1),
            new ButtonDef("/asset/middleware", "编辑中间件", "asset:middleware:edit", 2),
            new ButtonDef("/asset/middleware", "删除中间件", "asset:middleware:delete", 3),
            new ButtonDef("/asset/middleware", "探测中间件", "asset:middleware:probe", 4),
            new ButtonDef("/asset/app", "新增应用", "asset:app:add", 1),
            new ButtonDef("/asset/app", "编辑应用", "asset:app:edit", 2),
            new ButtonDef("/asset/app", "删除应用", "asset:app:delete", 3),
            new ButtonDef("/asset/project", "新增项目", "asset:project:add", 1),
            new ButtonDef("/asset/project", "编辑项目", "asset:project:edit", 2),
            new ButtonDef("/asset/project", "删除项目", "asset:project:delete", 3),
            new ButtonDef("/asset/project", "分配资产/角色", "asset:project:assign", 4),
            new ButtonDef("/alert/rule", "新增规则", "alert:rule:add", 1),
            new ButtonDef("/alert/rule", "编辑规则", "alert:rule:edit", 2),
            new ButtonDef("/alert/rule", "删除规则", "alert:rule:delete", 3),
            new ButtonDef("/alert/rule", "启用/停用", "alert:rule:toggle", 4),
            new ButtonDef("/exec/task", "执行命令", "exec:run", 1),
            new ButtonDef("/exec/task", "保存模板", "exec:template:save", 2),
            new ButtonDef("/exec/cron", "新增定时任务", "exec:cron:add", 1),
            new ButtonDef("/exec/cron", "编辑定时任务", "exec:cron:edit", 2),
            new ButtonDef("/exec/cron", "删除定时任务", "exec:cron:delete", 3),
            new ButtonDef("/settings", "保存通用设置", "settings:save", 1),
            new ButtonDef("/settings", "修改 AI 配置", "settings:ai", 2),
            new ButtonDef("/settings", "修改数据源", "settings:source", 3),
            new ButtonDef("/db", "执行 SQL", "db:execute", 1),
            new ButtonDef("/db", "测试连接", "db:test", 2),
            new ButtonDef("/asset/middleware/elk", "新增 ELK 连接", "elk:connection:add", 1),
            new ButtonDef("/asset/middleware/elk", "编辑 ELK 连接", "elk:connection:edit", 2),
            new ButtonDef("/asset/middleware/elk", "删除 ELK 连接", "elk:connection:delete", 3),
            new ButtonDef("/asset/middleware/elk", "测试 ELK 连接", "elk:connection:test", 4),
            new ButtonDef("/kb", "新建知识库", "kb:create", 1),
            new ButtonDef("/kb", "上传文档", "kb:upload", 2),
            new ButtonDef("/kb", "删除文档", "kb:delete", 3),
            new ButtonDef("/diagnosis", "发起诊断", "diagnosis:run", 1)
    );

    private static List<String> pages(String... p) { return Arrays.asList(p); }
    private static List<String> buttons(String... b) { return Arrays.asList(b); }

    private static final List<RoleDef> ROLES = Arrays.asList(
            new RoleDef("ROLE_SUPER_ADMIN", "超级管理员", "#ef4444", "GLOBAL",
                    "系统初始化、角色权限模型、全局配置、紧急兜底；不参与日常审批；操作强审计；建议双人复核",
                    pages(), buttons()),
            new RoleDef("ROLE_PLATFORM_OPS", "平台运维管理员", "#3b82f6", "GLOBAL",
                    "集群/组件巡检、服务启停、配置变更、版本升级、故障处理；可运维平台组件，但发布/变更需走流程",
                    pages("/asset/server", "/asset/middleware", "/asset/app", "/exec/task", "/exec/cron", "/exec/ssh",
                            "/diagnosis", "/log", "/alert/history", "/alert/rule", "/topology"),
                    buttons("asset:server:add", "asset:server:edit", "asset:server:delete", "asset:server:probe",
                            "asset:server:import", "asset:server:sync",
                            "asset:middleware:add", "asset:middleware:edit", "asset:middleware:delete", "asset:middleware:probe",
                            "asset:app:add", "asset:app:edit", "asset:app:delete",
                            "exec:run", "exec:template:save", "exec:cron:add", "exec:cron:edit", "exec:cron:delete",
                            "diagnosis:run")),
            new RoleDef("ROLE_MONITOR", "监控告警值班员", "#f59e0b", "GLOBAL",
                    "7×24 监控、告警确认、初判、派单、升级；以查看和告警处置为主，不直接改生产配置",
                    pages("/asset/server", "/asset/middleware", "/asset/app", "/alert/rule", "/alert/history",
                            "/log", "/diagnosis", "/topology"),
                    buttons("diagnosis:run", "asset:server:probe", "asset:middleware:probe")),
            new RoleDef("ROLE_CHANGE", "变更/发布管理员", "#8b5cf6", "GLOBAL",
                    "变更评审、发布窗口、灰度/回滚、变更冻结；审批与执行分离，不能自审自发布",
                    pages("/exec/task", "/exec/cron", "/exec/ssh", "/asset/app", "/topology", "/diagnosis",
                            "/log", "/alert/history", "/audit"),
                    buttons("exec:run", "exec:template:save", "exec:cron:add", "exec:cron:edit", "exec:cron:delete",
                            "asset:app:add", "asset:app:edit", "asset:app:delete", "diagnosis:run")),
            new RoleDef("ROLE_DATA_OPS", "数据/作业运维", "#06b6d4", "GLOBAL",
                    "数据任务调度、作业失败处理、队列资源、数据质量告警；管理作业与队列，不管理主机/网络安全策略",
                    pages("/db", "/exec/task", "/exec/cron", "/asset/app", "/log", "/alert/history", "/diagnosis"),
                    buttons("db:execute", "db:test", "exec:run", "exec:template:save",
                            "exec:cron:add", "exec:cron:edit", "exec:cron:delete", "diagnosis:run")),
            new RoleDef("ROLE_INFRA", "基础设施运维", "#0ea5e9", "GLOBAL",
                    "主机、网络、存储、虚拟化/容器底座运维；管底座资源，不直接操作业务数据",
                    pages("/asset/server", "/asset/middleware", "/asset/app", "/exec/task", "/exec/cron", "/exec/ssh",
                            "/topology", "/log", "/alert/history", "/diagnosis"),
                    buttons("asset:server:add", "asset:server:edit", "asset:server:delete", "asset:server:probe",
                            "asset:server:import", "asset:server:sync",
                            "asset:middleware:add", "asset:middleware:edit", "asset:middleware:delete", "asset:middleware:probe",
                            "asset:app:add", "asset:app:edit", "asset:app:delete",
                            "exec:run", "exec:template:save", "exec:cron:add", "exec:cron:edit", "exec:cron:delete",
                            "diagnosis:run")),
            new RoleDef("ROLE_DBA", "DBA", "#d97706", "GLOBAL",
                    "数据库实例、账号、备份恢复、慢SQL、容量；生产数据变更需审批；敏感数据操作审计",
                    pages("/db", "/asset/middleware", "/asset/server", "/exec/task", "/log", "/alert/history",
                            "/diagnosis", "/audit"),
                    buttons("db:execute", "db:test", "asset:middleware:add", "asset:middleware:edit",
                            "asset:middleware:delete", "asset:middleware:probe", "asset:server:probe",
                            "exec:run", "diagnosis:run")),
            new RoleDef("ROLE_SECURITY", "安全管理员", "#ec4899", "GLOBAL",
                    "账号权限、密钥、安全基线、漏洞与合规策略；不承担日常运维执行，避免“既管安全又管操作”",
                    pages("/system/user", "/system/role", "/system/menu", "/settings", "/audit", "/log"),
                    buttons("sys:user:add", "sys:user:edit", "sys:user:delete",
                            "settings:save", "settings:ai", "settings:source")),
            new RoleDef("ROLE_AUDITOR", "审计员", "#64748b", "READONLY",
                    "查看操作日志、审批记录、权限变更、合规审计；只读，不可修改配置和日志",
                    pages("/audit", "/log", "/system/user", "/system/role", "/topology", "/alert/history"),
                    buttons()),
            new RoleDef("ROLE_PROJECT_ADMIN", "租户/项目管理员", "#22c55e", "PROJECT",
                    "本租户用户、资源配额、作业权限、项目级配置；仅限所属租户，不能跨租户",
                    pages("/asset/project", "/asset/server", "/asset/middleware", "/asset/app",
                            "/exec/task", "/exec/cron", "/alert/history", "/topology", "/log"),
                    buttons("asset:project:add", "asset:project:edit", "asset:project:delete", "asset:project:assign",
                            "asset:server:edit", "asset:server:probe", "asset:middleware:probe", "exec:run")),
            new RoleDef("ROLE_SERVICE_DESK", "服务台/一线支持", "#14b8a6", "TENANT",
                    "受理工单、分类派单、跟踪闭环、用户沟通；不直接拥有生产变更权限",
                    pages("/asset/server", "/asset/middleware", "/asset/app", "/alert/history", "/log",
                            "/diagnosis", "/topology"),
                    buttons("diagnosis:run", "asset:server:probe", "asset:middleware:probe")),
            new RoleDef("ROLE_VIEWER", "只读观察者", "#94a3b8", "READONLY",
                    "监控大屏、报表、运行状态查看；全局只读或限定范围只读",
                    pages("/asset/server", "/asset/middleware", "/asset/app", "/alert/history", "/log", "/topology"),
                    buttons("asset:server:probe", "asset:middleware:probe"))
    );
}