-- =============================================================================
-- 新增「运维执行 → Web 终端」菜单（可重复执行）
-- 适用于已初始化的数据库；新库直接使用 data.sql 即可。
-- =============================================================================
INSERT INTO sys_menu (parent_id, name, path, component, icon, sort, permission, type)
SELECT 18, 'Web 终端', '/exec/ssh', 'exec/ssh/index', 'Monitor', 3, 'exec:ssh', 1
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/exec/ssh');

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, m.id FROM sys_menu m
WHERE m.path = '/exec/ssh'
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = 1 AND rm.menu_id = m.id);