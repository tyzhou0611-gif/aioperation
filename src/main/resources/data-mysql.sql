-- MySQL 模式不自动执行种子数据，避免每次启动覆盖业务数据。
-- 初始化数据请手动执行 data.sql（已兼容 MySQL REPLACE INTO）。
-- 这里保留一条无害语句，避免 Spring 把空脚本视为非法。
SELECT 1;