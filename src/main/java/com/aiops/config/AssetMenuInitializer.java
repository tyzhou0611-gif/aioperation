package com.aiops.config;

import com.aiops.entity.SysMenu;
import com.aiops.entity.SysRole;
import com.aiops.repository.SysMenuRepository;
import com.aiops.repository.SysRoleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 资产管理菜单增量初始化：
 * 已有数据库不会重新执行 RBAC 全量 seed，这里每次启动幂等补齐
 * “资产管理 → FTP 服务器”页面与按钮权限，避免升级后菜单消失。
 */
@Component
public class AssetMenuInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AssetMenuInitializer.class);

    private final SysMenuRepository menuRepo;
    private final SysRoleRepository roleRepo;

    public AssetMenuInitializer(SysMenuRepository menuRepo, SysRoleRepository roleRepo) {
        this.menuRepo = menuRepo;
        this.roleRepo = roleRepo;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        try {
            ensureFtpMenus();
        } catch (Exception e) {
            log.warn("FTP 资产菜单初始化失败（不影响启动，可在菜单管理中手工添加）: {}", e.getMessage());
        }
    }

    private void ensureFtpMenus() {
        SysMenu parent = null;
        for (SysMenu m : menuRepo.findAll()) {
            if ("/asset".equals(m.getPath()) || "资产管理".equals(m.getName())) { parent = m; break; }
        }
        if (parent == null) {
            log.warn("未找到资产管理菜单，跳过 FTP 菜单初始化");
            return;
        }
        SysMenu page = findByPath("/asset/ftp");
        if (page == null) {
            page = new SysMenu();
            page.setParentId(parent.getId());
            page.setName("FTP 服务器");
            page.setPath("/asset/ftp");
            page.setComponent("asset/ftp/index");
            page.setIcon("Connection");
            page.setSort(5);
            page.setPermission("asset:ftp:list");
            page.setType(1);
            page = menuRepo.save(page);
            log.info("已创建 FTP 服务器菜单 id={}", page.getId());
        }
        SysMenu test = ensureButton(page.getId(), "测试连接", "asset:ftp:test", 1);
        SysMenu edit = ensureButton(page.getId(), "FTP 编辑", "asset:ftp:edit", 2);
        SysMenu transfer = ensureButton(page.getId(), "FTP 拉取/分发", "asset:ftp:transfer", 3);

        for (SysRole role : roleRepo.findAll()) {
            if (role.getMenus() == null) role.setMenus(new HashSet<SysMenu>());
            boolean superRole = "ROLE_SUPER_ADMIN".equalsIgnoreCase(role.getName())
                    || "ROLE_ADMIN".equalsIgnoreCase(role.getName());
            boolean hasAsset = false;
            boolean canEditAsset = false;
            for (SysMenu m : role.getMenus()) {
                if (m.getPath() != null && m.getPath().startsWith("/asset")) hasAsset = true;
                String p = m.getPermission();
                if (p != null && (p.equals("asset:server:edit") || p.equals("asset:middleware:edit")
                        || p.equals("asset:app:edit") || p.equals("asset:project:edit"))) canEditAsset = true;
            }
            if (superRole) {
                boolean changed = addIfMissing(role, page);
                changed = addIfMissing(role, test) || changed;
                changed = addIfMissing(role, edit) || changed;
                changed = addIfMissing(role, transfer) || changed;
                if (changed) roleRepo.save(role);
            } else if (hasAsset) {
                boolean changed = addIfMissing(role, page);
                if (canEditAsset) {
                    changed = addIfMissing(role, test) || changed;
                    changed = addIfMissing(role, edit) || changed;
                    changed = addIfMissing(role, transfer) || changed;
                }
                if (changed) roleRepo.save(role);
            }
        }
    }

    private boolean addIfMissing(SysRole role, SysMenu menu) {
        for (SysMenu m : role.getMenus()) {
            if (m.getId() != null && m.getId().equals(menu.getId())) return false;
        }
        role.getMenus().add(menu);
        return true;
    }

    private SysMenu ensureButton(Long parentId, String name, String permission, int sort) {
        for (SysMenu m : menuRepo.findAll()) {
            if (permission.equals(m.getPermission())) return m;
        }
        SysMenu m = new SysMenu();
        m.setParentId(parentId);
        m.setName(name);
        m.setPath("");
        m.setComponent("");
        m.setIcon("");
        m.setSort(sort);
        m.setPermission(permission);
        m.setType(2);
        return menuRepo.save(m);
    }

    private SysMenu findByPath(String path) {
        for (SysMenu m : menuRepo.findAll()) {
            if (path.equals(m.getPath())) return m;
        }
        return null;
    }
}