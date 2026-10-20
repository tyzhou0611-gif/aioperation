package com.aiops.service;

import com.aiops.common.ServiceException;
import com.aiops.entity.SysMenu;
import com.aiops.entity.SysRole;
import com.aiops.entity.SysUser;
import com.aiops.repository.SysMenuRepository;
import com.aiops.repository.SysUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 系统菜单服务
 * 负责构建动态菜单树
 */
@Service
@RequiredArgsConstructor
public class SysMenuService {

    private final SysMenuRepository menuRepository;
    private final SysUserRepository userRepository;

    /**
     * 根据用户名获取菜单树
     */
    public List<MenuTreeNode> getMenuTreeByUsername(String username) {
        SysUser user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ServiceException(401, "用户不存在"));

        // 收集用户所有角色关联的菜单ID
        Set<Long> menuIds = user.getRoles().stream()
                .flatMap(role -> role.getMenus().stream())
                .map(SysMenu::getId)
                .collect(Collectors.toSet());

        // 如果用户没有任何菜单权限，返回空树
        if (menuIds.isEmpty()) {
            return Collections.emptyList();
        }

        // 加载所有菜单并过滤
        List<SysMenu> allMenus = menuRepository.findAllByOrderBySortAsc();
        List<SysMenu> userMenus = allMenus.stream()
                .filter(m -> menuIds.contains(m.getId()))
                .collect(Collectors.toList());

        // 构建树
        return buildTree(userMenus);
    }

    /**
     * 将菜单列表构建为树形结构
     */
    private List<MenuTreeNode> buildTree(List<SysMenu> menus) {
        // 收集所有需要补充的父节点ID：当前菜单的父节点如果在菜单列表中不存在（但属于目录类型）
        // 需要自动补充到树中
        Set<Long> existingIds = menus.stream().map(SysMenu::getId).collect(Collectors.toSet());
        Set<Long> neededParentIds = new HashSet<>();

        for (SysMenu menu : menus) {
            if (menu.getParentId() != null && menu.getParentId() > 0
                    && !existingIds.contains(menu.getParentId())) {
                neededParentIds.add(menu.getParentId());
            }
        }

        // 补充缺失的父节点
        List<SysMenu> fullMenus = new ArrayList<>(menus);
        if (!neededParentIds.isEmpty()) {
            List<SysMenu> parents = menuRepository.findAllById(neededParentIds);
            for (SysMenu parent : parents) {
                if (!fullMenus.contains(parent)) {
                    fullMenus.add(parent);
                }
            }
        }

        // 按 parentId 分组
        Map<Long, List<SysMenu>> parentMap = fullMenus.stream()
                .collect(Collectors.groupingBy(
                        m -> m.getParentId() != null ? m.getParentId() : 0L,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        // 从根节点（parentId=0）开始递归构建
        return buildChildren(parentMap, 0L);
    }

    /**
     * 递归构建子节点
     */
    private List<MenuTreeNode> buildChildren(Map<Long, List<SysMenu>> parentMap, Long parentId) {
        List<SysMenu> children = parentMap.getOrDefault(parentId, Collections.emptyList());
        return children.stream()
                .sorted(Comparator.comparingInt(m -> m.getSort() != null ? m.getSort() : 0))
                .map(menu -> {
                    MenuTreeNode node = MenuTreeNode.builder()
                            .id(menu.getId())
                            .parentId(menu.getParentId())
                            .name(menu.getName())
                            .path(menu.getPath())
                            .component(menu.getComponent())
                            .icon(menu.getIcon())
                            .sort(menu.getSort())
                            .permission(menu.getPermission())
                            .type(menu.getType())
                            .build();
                    node.setChildren(buildChildren(parentMap, menu.getId()));
                    return node;
                })
                .collect(Collectors.toList());
    }

    /**
     * 菜单树节点 VO
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class MenuTreeNode {
        private Long id;
        private Long parentId;
        private String name;
        private String path;
        private String component;
        private String icon;
        private Integer sort;
        private String permission;
        private Integer type;
        private List<MenuTreeNode> children;
    }
}