-- =============================================================================
-- AI-Ops Platform - 初始化数据
-- 使用REPLACE INTO（H2语法）确保幂等性，重复执行不会报错
-- =============================================================================

-- 默认角色
REPLACE INTO sys_role (id, name, description) VALUES
(1, 'ROLE_ADMIN', '系统管理员 - 拥有所有权限'),
(2, 'ROLE_USER', '普通用户 - 基本访问权限');

-- 默认管理员账户 admin / 123456（BCrypt加密）
-- 加密值：$2a$10$mfMzouV5lIubc5lV2.EbTOif1GN8BTjyN06iexywWgkRM.dV3WYqi
-- 由 BCryptPasswordEncoder().encode("123456") 生成
REPLACE INTO sys_user (id, username, password, email, nickname, status) VALUES
(1, 'admin', '$2a$10$mfMzouV5lIubc5lV2.EbTOif1GN8BTjyN06iexywWgkRM.dV3WYqi', 'admin@example.com', 'Administrator', 0);

-- 关联用户角色（admin -> ROLE_ADMIN）
REPLACE INTO sys_user_role (id, user_id, role_id) VALUES (1, 1, 1);

-- 默认菜单
REPLACE INTO sys_menu (id, parent_id, name, path, component, icon, sort, permission, type) VALUES
(1, 0, '系统管理', '/system', '', 'Setting', 1, '', 0),
(2, 1, '用户管理', '/system/user', 'system/user/index', 'User', 1, 'sys:user:list', 1),
(3, 1, '角色管理', '/system/role', 'system/role/index', 'Role', 2, 'sys:role:list', 1),
(4, 1, '菜单管理', '/system/menu', 'system/menu/index', 'Menu', 3, 'sys:menu:list', 1),
(5, 0, '资产管理', '/asset', '', 'Monitor', 2, '', 0),
(6, 5, '服务器列表', '/asset/server', 'asset/server/index', 'Computer', 1, 'asset:server:list', 1),
(7, 5, '添加服务器', '', '', '', 2, 'asset:server:add', 2);

-- 角色菜单关联（admin 拥有所有菜单）
REPLACE INTO sys_role_menu (id, role_id, menu_id) VALUES
(1, 1, 1), (2, 1, 2), (3, 1, 3), (4, 1, 4),
(5, 1, 5), (6, 1, 6), (7, 1, 7);

-- 新增：监控告警菜单
REPLACE INTO sys_menu (id, parent_id, name, path, component, icon, sort, permission, type) VALUES
(8, 0, '监控告警', '/alert', '', 'Warning', 3, '', 0),
(9, 8, '告警规则', '/alert/rule', 'alert/rule/index', 'List', 1, 'alert:rule:list', 1),
(10, 8, '告警历史', '/alert/history', 'alert/history/index', 'Timer', 2, 'alert:history:list', 1);

-- 关联新菜单到 admin 角色
REPLACE INTO sys_role_menu (id, role_id, menu_id) VALUES
(8, 1, 8), (9, 1, 9), (10, 1, 10);

-- 第7批新菜单
REPLACE INTO sys_menu (id, parent_id, name, path, component, icon, sort, permission, type) VALUES
(11, 0, '日志管理', '/log', 'log/index', 'Document', 4, 'log:view', 1),
(12, 0, '智能诊断', '/diagnosis', 'diagnosis/index', 'Aim', 5, 'diagnosis:view', 1),
(13, 0, '审计日志', '/audit', 'audit/index', 'View', 6, 'audit:view', 1),
(14, 0, '系统设置', '/settings', 'settings/index', 'Tools', 99, 'settings:view', 1);

REPLACE INTO sys_role_menu (id, role_id, menu_id) VALUES
(11, 1, 11), (12, 1, 12), (13, 1, 13), (14, 1, 14);

-- 服务器资产不预置：请在【资产管理 → 服务器列表】中添加真实主机，
-- 或使用批量导入（Xshell / SecureCRT / OpenSSH / CSV / JSON）导入现有资产。

-- 告警规则不预置：请按实际环境在【监控告警 → 告警规则】中创建。

-- 日志不预置：请在【系统设置 → 日志源】接入 Loki / Elasticsearch 后查看真实日志。

-- ============================================================================
-- 第8批：资产菜单
-- ============================================================================
REPLACE INTO sys_menu (id, parent_id, name, path, component, icon, sort, permission, type) VALUES
(15, 5, '中间件管理', '/asset/middleware-dir', '', 'Coin', 2, '', 0),
(25, 15, '中间件列表', '/asset/middleware', 'asset/middleware/index', 'List', 1, 'asset:middleware:list', 1),
(16, 5, '应用管理', '/asset/app', 'asset/app/index', 'Grid', 3, 'asset:app:list', 1),
(17, 0, '智能大屏', '/topology', 'topology/index', 'Share', 3, 'topology:view', 1);

REPLACE INTO sys_role_menu (id, role_id, menu_id) VALUES
(15, 1, 15), (16, 1, 16), (17, 1, 17), (25, 1, 25);

-- ============================================================================
-- 中间件 / 应用资产不预置：请在【资产管理】中按实际环境添加。
-- ============================================================================

-- 预设命令模板
REPLACE INTO command_template (id, name, target_type, middleware_type, command, description, dangerous) VALUES
(1, '查看磁盘使用', 'SERVER', NULL, 'df -h', '查看各挂载点磁盘使用率', 0),
(2, '查看内存使用', 'SERVER', NULL, 'free -h', '查看系统内存使用情况', 0),
(3, '查看端口占用', 'SERVER', NULL, 'netstat -tlnp', '查看监听端口与占用进程', 0),
(4, '查看MySQL状态', 'MIDDLEWARE', 'MYSQL', 'systemctl status mysqld', '查看MySQL服务运行状态', 0),
(5, '重启MySQL', 'MIDDLEWARE', 'MYSQL', 'systemctl restart mysqld', '重启MySQL服务(危险)', 1),
(6, '查看Kafka Topic列表', 'MIDDLEWARE', 'KAFKA', 'kafka-topics.sh --list --bootstrap-server localhost:9092', '列出所有Kafka主题', 0),
(7, '查看Nacos健康', 'MIDDLEWARE', 'NACOS', 'curl -s http://localhost:8848/nacos/actuator/health', '查看Nacos健康状态', 0),
(8, '查看Redis信息', 'MIDDLEWARE', 'REDIS', 'redis-cli info server', '查看Redis服务端信息', 0),
(9, '查看应用日志', 'APP', NULL, 'tail -n 100 /opt/app/logs/app.log', '查看应用最近100行日志', 0),
(10, '重启应用', 'APP', NULL, 'systemctl restart $APP', '重启指定应用(危险)', 1),
(12, '查磁盘 TOP10', 'SERVER', NULL, 'df -h | sort -rhk5 | head -10', '按使用率查看磁盘 Top10', 0),
(13, '重启服务 {service}', 'SERVER', NULL, 'systemctl restart {service}', '参数化：重启指定 systemd 服务', 1),
(14, '清理 N 天前日志', 'SERVER', NULL, 'find {path} -type f -name "*.log" -mtime +{days} -delete', '参数化：删除指定目录下 N 天前日志（危险）', 1),
(15, '查看端口监听 {port}', 'SERVER', NULL, 'ss -lntp | grep {port}', '参数化：查看指定端口监听情况', 0),
(16, '内存 TOP10 进程', 'SERVER', NULL, 'ps -eo pid,ppid,comm,%mem,%cpu --sort=-%mem | head -10', '查看内存占用最高的 10 个进程', 0);

-- ============================================================================
-- 第9批：AI Copilot 本地规则模板（无大模型时的降级知识库，20条常见运维场景）
-- ============================================================================
REPLACE INTO ai_solution_template (id, keywords, title, summary, steps, code_blocks, risk_level, risk_tip) VALUES
(1, 'kafka,消费者,延迟,lazy,lag,堆积', '排查Kafka消费者组Lag', '查看消费者组积压并定位消费慢的原因',
 '["查看所有消费者组的 lag","对高 lag 的 topic 检查消费端线程/GC","确认 broker 与 consumer 版本与分区分配","必要时扩容消费实例或重置 offset"]',
 '[{"language":"bash","label":"查看消费者组lag","code":"kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --all-groups"},{"language":"bash","label":"重置offset到最新","code":"kafka-consumer-groups.sh --bootstrap-server localhost:9092 --group <GROUP> --reset-offsets --to-latest --topic <TOPIC> --execute"}]',
 'MEDIUM', '重置offset会丢弃未消费消息，务必先确认业务可接受'),
(2, 'kafka,topic,列表,主题', '查看Kafka Topic列表', '列出全部topic及其分区副本',
 '["使用 kafka-topics.sh 列出主题","describe 查看具体分区/副本分布"]',
 '[{"language":"bash","label":"列出topic","code":"kafka-topics.sh --bootstrap-server localhost:9092 --list"},{"language":"bash","label":"查看topic详情","code":"kafka-topics.sh --bootstrap-server localhost:9092 --describe --topic <TOPIC>"}]',
 'LOW', ''),
(3, 'kafka,broker,状态,集群', '查看Kafka Broker状态', '确认集群在线broker与ISR',
 '["zookeeper/kraft 查看 broker 列表","检查 ISR 是否完整"]',
 '[{"language":"bash","label":"broker列表","code":"kafka-broker-api-versions.sh --bootstrap-server localhost:9092 | head"},{"language":"bash","label":"under-replicated分区","code":"kafka-topics.sh --bootstrap-server localhost:9092 --describe --under-replicated-partitions"}]',
 'LOW', ''),
(4, 'mysql,主从,复制,同步,断了,slave', 'MySQL主从复制排查', '检查复制线程与延迟并修复',
 '["在从库执行 SHOW SLAVE STATUS","查看 IO/SQL 线程是否 Running 及 Seconds_Behind","若报错定位错误号，跳过或重建主从","必要时用 pt-online 或重新搭建复制"]',
 '[{"language":"sql","label":"查看复制状态","code":"SHOW SLAVE STATUS\\G"},{"language":"sql","label":"重启复制线程","code":"STOP SLAVE; START SLAVE;"}]',
 'MEDIUM', '跳过事务或重搭主从有丢数据风险，需评估'),
(5, 'mysql,慢查询,slow,慢sql', '分析MySQL慢查询', '开启并定位慢查询',
 '["确认 slow_query_log 开启","查看慢日志 top SQL","用 EXPLAIN 分析执行计划","加索引或改写SQL"]',
 '[{"language":"sql","label":"查看慢查询配置","code":"SHOW VARIABLES LIKE "slow_query%";"},{"language":"sql","label":"执行计划","code":"EXPLAIN SELECT ... ;"}]',
 'LOW', ''),
(6, 'mysql,连接数,max_connections,too many connections', 'MySQL连接数打满处理', '排查连接泄漏并调优',
 '["SHOW PROCESSLIST 看活跃连接来源","确认 max_connections 与 wait_timeout","定位未释放连接的应用","必要时 kill 异常连接或调大上限"]',
 '[{"language":"sql","label":"查看连接","code":"SHOW PROCESSLIST;"},{"language":"sql","label":"查看上限","code":"SHOW VARIABLES LIKE "max_connections";"}]',
 'MEDIUM', 'kill 连接会影响业务，需谨慎'),
(7, 'nacos,健康,状态,服务列表', 'Nacos服务健康检查', '确认nacos与注册服务状态',
 '["curl 健康接口","查询注册的服务实例列表"]',
 '[{"language":"bash","label":"健康检查","code":"curl -s http://localhost:8848/nacos/actuator/health"},{"language":"bash","label":"服务列表","code":"curl -s "http://localhost:8848/nacos/v1/ns/service/list?pageNo=1&pageSize=20\""}]',
 'LOW', ''),
(8, 'redis,内存,memory,info', 'Redis内存与信息', '查看内存与命中率',
 '["redis-cli info memory 看 used/limit","info stats 看命中率","必要时配置 maxmemory 与淘汰策略"]',
 '[{"language":"bash","label":"内存信息","code":"redis-cli info memory"},{"language":"bash","label":"命中率","code":"redis-cli info stats | grep keyspace"}]',
 'LOW', ''),
(9, 'redis,bigkey,大key,慢', '排查Redis大Key/慢命令', '定位大key与慢日志',
 '["redis-cli --bigkeys 扫描大key","SLOWLOG GET 看慢命令","对大key做拆分或过期"]',
 '[{"language":"bash","label":"扫描大key","code":"redis-cli --bigkeys"},{"language":"bash","label":"慢日志","code":"redis-cli slowlog get 20"}]',
 'LOW', ''),
(10, '磁盘,disk,df,满,空间,no space', '磁盘空间满清理', '定位占用并清理',
 '["df -h 看哪个挂载点满","du -sh 大目录定位","清理日志/临时文件/回收容器镜像","设置日志轮转避免再次打满"]',
 '[{"language":"bash","label":"磁盘概览","code":"df -h"},{"language":"bash","label":"定位大目录","code":"du -sh /* 2>/dev/null | sort -rh | head -10"}]',
 'MEDIUM', '删除文件前确认非业务数据'),
(11, '内存,mem,free,oom,溢出,swap', '内存不足/OOM排查', '定位内存大户与OOM',
 '["free -h 看整体","top/ps 按内存排序找进程","dmesg 查 OOM 记录","调 JVM/容器内存或扩容"]',
 '[{"language":"bash","label":"内存概览","code":"free -h"},{"language":"bash","label":"内存TOP10进程","code":"ps aux --sort=-%mem | head -10"}]',
 'LOW', ''),
(12, 'cpu,负载,load,高,打满', 'CPU/负载过高排查', '定位高CPU进程与线程',
 '["top 看负载与占用","pidstat -t 找高CPU线程","结合 jstack/火焰图定位代码热点","必要时扩容或限流"]',
 '[{"language":"bash","label":"实时负载","code":"top -b -n1 | head -20"},{"language":"bash","label":"线程级CPU","code":"pidstat -t -p <PID> 1 5"}]',
 'LOW', ''),
(13, '端口,占用,netstat,lsof,端口占用', '查看端口占用', '定位端口被谁监听',
 '["ss/netstat 查端口进程","确认后评估能否处理占用进程"]',
 '[{"language":"bash","label":"查端口","code":"ss -lntp | grep :<PORT>"}]',
 'LOW', ''),
(14, 'nginx,502,504,upstream,反向代理', 'Nginx 502/504排查', '后端不可达或超时',
 '["看 nginx error.log","curl 后端服务是否可达","检查 upstream keepalive/超时","修复后端或调大超时"]',
 '[{"language":"bash","label":"错误日志","code":"tail -n 100 /var/log/nginx/error.log"},{"language":"bash","label":"探活后端","code":"curl -sv http://127.0.0.1:8080/actuator/health"}]',
 'LOW', ''),
(15, '重启,restart,应用,服务,systemctl', '重启应用服务', '标准systemd重启',
 '["确认服务名","systemctl restart","观察 status 与日志"]',
 '[{"language":"bash","label":"重启并查看","code":"systemctl restart <SERVICE> && systemctl status <SERVICE>"}]',
 'HIGH', '重启会中断服务，需二次确认并输入 CONFIRM'),
(16, '日志,log,tail,查日志,报错', '查看应用日志定位报错', '按关键字捞异常',
 '["tail 实时跟踪","grep 关键字定位异常堆栈"]',
 '[{"language":"bash","label":"跟踪日志","code":"tail -f /opt/app/logs/app.log"},{"language":"bash","label":"查异常","code":"grep -i error /opt/app/logs/app.log | tail -50"}]',
 'LOW', ''),
(17, 'jvm,堆,heap,内存泄漏,gc,fatjar', 'JVM堆内存分析', '看堆与GC',
 '["jstat 看GC频率与耗时","jmap 导出堆","MAT/jhat 分析大对象"]',
 '[{"language":"bash","label":"GC统计","code":"jstat -gcutil <PID> 1000 5"},{"language":"bash","label":"导出堆","code":"jmap -dump:live,format=b,file=/tmp/heap.hprof <PID>"}]',
 'MEDIUM', 'jmap 导出会 STW，生产慎用'),
(18, 'docker,容器,重启,镜像,退出', 'Docker容器异常排查', '看容器状态与日志',
 '["docker ps -a 看退出码","docker logs 看报错","restart 或重建容器","镜像/磁盘问题则清理"]',
 '[{"language":"bash","label":"容器状态","code":"docker ps -a | head"},{"language":"bash","label":"容器日志","code":"docker logs --tail 100 <CONTAINER>"}]',
 'MEDIUM', '重启容器会中断其承载服务'),
(19, '服务,status,systemctl,运行,挂了,down', '查看服务运行状态', '确认服务是否存活',
 '["systemctl status 看 Active","失败则看 journal 日志定位"]',
 '[{"language":"bash","label":"服务状态","code":"systemctl status <SERVICE>"},{"language":"bash","label":"最近日志","code":"journalctl -u <SERVICE> -n 100 --no-pager"}]',
 'LOW', ''),
(20, '备份,backup,打包,脚本,定时', '目录备份脚本', '生成带时间戳的归档',
 '["确认备份目录与保留策略","编写 tar 归档带日期","配合 cron 定时","上传对象存储或异地"]',
 '[{"language":"bash","label":"打包备份","code":"tar czf /backup/app_$(date +%F).tar.gz -C /opt app"},{"language":"bash","label":"cron定时(每天2点)","code":"0 2 * * * tar czf /backup/app_$(date +\\%F).tar.gz -C /opt app"}]',
 'LOW', '备份路径需足够磁盘空间，避免把磁盘写满');

-- 第13批：给部分规则模板预置 navigation（跳转建议；params.id 由上下文资产运行时补全）
UPDATE ai_solution_template SET navigation = '{"target":"MIDDLEWARE_DETAIL","label":"查看该 Kafka 实例详情","reason":"在监控页查看该实例实时状态"}' WHERE id = 1;
UPDATE ai_solution_template SET navigation = '{"target":"MIDDLEWARE_DETAIL","label":"查看该 MySQL 实例详情","reason":"核对主从复制状态"}' WHERE id = 4;
UPDATE ai_solution_template SET navigation = '{"target":"SERVER_DETAIL","label":"查看该服务器详情","reason":"定位磁盘占用所在主机"}' WHERE id = 10;
UPDATE ai_solution_template SET navigation = '{"target":"LOG_MANAGEMENT","params":{"keyword":"ERROR"},"label":"查看错误日志","reason":"按 ERROR 关键字过滤日志"}' WHERE id = 16;
UPDATE ai_solution_template SET navigation = '{"target":"TOPOLOGY","label":"打开拓扑大屏","reason":"从全局视角查看依赖关系"}' WHERE id = 2;

-- ============================================================================
-- 资产拓扑关系不预置：请在【智能大屏 → 编辑模式】或【项目管理】中按实际依赖连线。
-- ============================================================================

-- ============================================================================
-- 第13批：运维执行菜单（命令执行 / 定时任务）
-- ============================================================================
REPLACE INTO sys_menu (id, parent_id, name, path, component, icon, sort, permission, type) VALUES
(18, 0, '运维执行', '/exec', '', 'Operation', 6, '', 0),
(19, 18, '命令执行', '/exec/task', 'exec/task/index', 'Cpu', 1, 'exec:task', 1),
(20, 18, '定时任务', '/exec/cron', 'exec/cron/index', 'Clock', 2, 'exec:cron', 1);

REPLACE INTO sys_role_menu (id, role_id, menu_id) VALUES
(18, 1, 18), (19, 1, 19), (20, 1, 20);

-- ============================================================================
-- 第15批：知识库管理菜单
-- ============================================================================
REPLACE INTO sys_menu (id, parent_id, name, path, component, icon, sort, permission, type) VALUES
(21, 0, '知识库', '/kb', 'kb/index', 'Collection', 8, 'kb:view', 1);

REPLACE INTO sys_role_menu (id, role_id, menu_id) VALUES
(21, 1, 21);

-- 注：AI 相关 sys_config 不在此预置，由 ConfigService 启动时以 application.yml 的 app.ai.* 为默认值 seed 入库
-- （避免 DB 旧值覆盖 yml；页面修改后写入 sys_config 并热生效）。
-- ============================================================================
-- 第16批：数据库工作台 / ELK管理菜单
-- ============================================================================
REPLACE INTO sys_menu (id, parent_id, name, path, component, icon, sort, permission, type) VALUES
(22, 0, 'SQL工作台', '/db', 'db/index', 'Coin', 9, 'db:workbench', 1),
(23, 15, 'ELK管理', '/asset/middleware/elk', 'elk/index', 'Search', 2, 'elk:manage', 1);

REPLACE INTO sys_role_menu (id, role_id, menu_id) VALUES
(22, 1, 22), (23, 1, 23);

-- ============================================================================
-- 第17批：项目管理菜单（挂在资产管理下）
-- ============================================================================
REPLACE INTO sys_menu (id, parent_id, name, path, component, icon, sort, permission, type) VALUES
(24, 5, '项目管理', '/asset/project', 'asset/project/index', 'Folder', 4, 'asset:project:list', 1);

REPLACE INTO sys_role_menu (id, role_id, menu_id) VALUES
(24, 1, 24);

-- ============================================================================
-- 第18批：Web SSH 交互式终端（挂在运维执行下）
-- ============================================================================
REPLACE INTO sys_menu (id, parent_id, name, path, component, icon, sort, permission, type) VALUES
(26, 18, 'Web 终端', '/exec/ssh', 'exec/ssh/index', 'Monitor', 3, 'exec:ssh', 1);

REPLACE INTO sys_role_menu (id, role_id, menu_id) VALUES
(26, 1, 26);
