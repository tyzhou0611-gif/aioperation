-- =============================================================================
-- AI-Ops Platform - 数据库建表脚本
-- 兼容 H2 和 MySQL（H2以MySQL模式启动 MODE=MySQL）
-- 使用 CREATE TABLE IF NOT EXISTS 确保幂等性
-- =============================================================================

-- 用户表
CREATE TABLE IF NOT EXISTS sys_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password VARCHAR(200) NOT NULL,
    email VARCHAR(100),
    nickname VARCHAR(50),
    avatar VARCHAR(500),
    status INT DEFAULT 0 COMMENT '0-正常 1-停用',
    dashboard_style VARCHAR(20) DEFAULT 'tech' COMMENT '仪表盘界面风格: tech/normal',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
-- 兼容旧库：仪表盘界面风格
ALTER TABLE sys_user ADD COLUMN dashboard_style VARCHAR(20) DEFAULT 'tech';

-- 角色表
CREATE TABLE IF NOT EXISTS sys_role (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(50) NOT NULL UNIQUE COMMENT '角色标识，如 ROLE_SUPER_ADMIN',
    label VARCHAR(50) COMMENT '中文角色名称',
    description VARCHAR(200),
    tag_color VARCHAR(20) COMMENT '标签颜色 #RRGGBB',
    builtin INT DEFAULT 0 COMMENT '1-内置角色 0-自定义角色',
    scope VARCHAR(20) DEFAULT 'GLOBAL' COMMENT 'GLOBAL/TENANT/PROJECT/READONLY',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
-- 兼容旧库：角色展示标签/内置标记/数据范围
ALTER TABLE sys_role ADD COLUMN label VARCHAR(50);
ALTER TABLE sys_role ADD COLUMN tag_color VARCHAR(20);
ALTER TABLE sys_role ADD COLUMN builtin INT DEFAULT 0;
ALTER TABLE sys_role ADD COLUMN scope VARCHAR(20) DEFAULT 'GLOBAL';

-- 菜单表
CREATE TABLE IF NOT EXISTS sys_menu (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    parent_id BIGINT DEFAULT 0 COMMENT '父菜单ID',
    name VARCHAR(50) NOT NULL COMMENT '菜单名称',
    path VARCHAR(200) COMMENT '路由路径',
    component VARCHAR(200) COMMENT '组件路径',
    icon VARCHAR(100) COMMENT '图标',
    sort INT DEFAULT 0 COMMENT '排序号',
    permission VARCHAR(100) COMMENT '权限标识，如 sys:user:list',
    type INT DEFAULT 0 COMMENT '0-目录 1-菜单 2-按钮',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 用户-角色关联表
CREATE TABLE IF NOT EXISTS sys_user_role (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    CONSTRAINT uk_user_role UNIQUE (user_id, role_id)
);

-- 角色-菜单关联表
CREATE TABLE IF NOT EXISTS sys_role_menu (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    role_id BIGINT NOT NULL,
    menu_id BIGINT NOT NULL,
    CONSTRAINT uk_role_menu UNIQUE (role_id, menu_id)
);

-- 服务器资产表
CREATE TABLE IF NOT EXISTS server_asset (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    ip VARCHAR(45) NOT NULL COMMENT 'IP地址',
    hostname VARCHAR(100) COMMENT '主机名',
    os VARCHAR(100) COMMENT '操作系统',
    cpu VARCHAR(50) COMMENT 'CPU信息',
    memory VARCHAR(50) COMMENT '内存信息',
    disk VARCHAR(50) COMMENT '磁盘信息',
    status VARCHAR(20) DEFAULT 'ONLINE' COMMENT 'ONLINE-在线 OFFLINE-离线 MAINTENANCE-维护',
    status_source VARCHAR(30) COMMENT '最后状态来源: prometheus/ssh/tcp/icmp',
    last_probe_time TIMESTAMP NULL COMMENT '最后一次状态探测时间',
    environment VARCHAR(20) DEFAULT 'production' COMMENT '环境: dev/test/staging/production',
    ssh_port INT DEFAULT 22 COMMENT 'SSH端口',
    ssh_user VARCHAR(50) DEFAULT 'root' COMMENT 'SSH用户名',
    ssh_password VARCHAR(500) COMMENT 'SSH密码(旧明文字段，迁移后置空)',
    ssh_password_encrypted VARCHAR(500) COMMENT 'SSH密码(AES-256密文)',
    ssh_key TEXT COMMENT 'SSH私钥内容',
    exporter_url VARCHAR(500) COMMENT 'Prometheus Exporter 地址',
    prometheus_job_name VARCHAR(100) COMMENT 'Prometheus job 名称',
    monitor_enabled INT DEFAULT 0 COMMENT '是否启用监控 0-否 1-是',
    tags VARCHAR(1000) COMMENT '标签 JSON 数组',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 创建索引
CREATE INDEX idx_sys_user_username ON sys_user(username);
CREATE INDEX idx_sys_role_name ON sys_role(name);
CREATE INDEX idx_sys_menu_parent_id ON sys_menu(parent_id);
CREATE INDEX idx_server_asset_ip ON server_asset(ip);
CREATE INDEX idx_server_asset_status ON server_asset(status);
-- 兼容旧库：补充 SSH 密码密文列
ALTER TABLE server_asset ADD COLUMN ssh_password_encrypted VARCHAR(500);
-- 兼容旧库：状态最后探测时间
ALTER TABLE server_asset ADD COLUMN last_probe_time TIMESTAMP NULL;
-- 兼容旧库：状态来源（prometheus/ssh/tcp/icmp）
ALTER TABLE server_asset ADD COLUMN status_source VARCHAR(30);
-- 兼容旧库：服务器标签
ALTER TABLE server_asset ADD COLUMN tags VARCHAR(1000);

-- ============================================================================
-- 告警规则表
-- ============================================================================
CREATE TABLE IF NOT EXISTS alert_rule (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    rule_name VARCHAR(100) NOT NULL COMMENT '规则名称',
    metric_name VARCHAR(50) NOT NULL COMMENT '指标名: CPU_USAGE/MEM_USAGE/DISK_USAGE',
    comparator VARCHAR(5) NOT NULL DEFAULT '>' COMMENT '比较符: > < =',
    threshold DOUBLE NOT NULL COMMENT '阈值',
    level VARCHAR(20) DEFAULT 'WARNING' COMMENT '告警级别: WARNING/CRITICAL',
    notify_email VARCHAR(200) COMMENT '通知邮箱',
    notify_channels VARCHAR(500) COMMENT '通知渠道JSON: ["email","dingtalk","wechat"]',
    notify_config VARCHAR(2000) COMMENT '通知配置JSON: {dingtalk:{webhook:...}}',
    auto_recovery_enabled INT DEFAULT 0 COMMENT '0-关闭 1-启用自动自愈',
    auto_recovery_script TEXT COMMENT '自愈Shell脚本',
    enabled INT DEFAULT 1 COMMENT '0-禁用 1-启用',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 告警历史表
CREATE TABLE IF NOT EXISTS alert_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    rule_id BIGINT COMMENT '关联规则ID',
    server_id BIGINT COMMENT '关联服务器ID',
    server_ip VARCHAR(45) COMMENT '服务器IP',
    metric_name VARCHAR(50) NOT NULL COMMENT '指标名',
    actual_value DOUBLE NOT NULL COMMENT '实际值',
    threshold DOUBLE NOT NULL COMMENT '阈值',
    comparator VARCHAR(5) DEFAULT '>' COMMENT '比较符',
    level VARCHAR(20) DEFAULT 'WARNING' COMMENT '告警级别',
    message VARCHAR(500) COMMENT '告警消息',
    status VARCHAR(20) DEFAULT 'UNREAD' COMMENT '状态: UNREAD/READ/RESOLVED',
    auto_heal_status VARCHAR(20) COMMENT '自愈状态: SUCCESS/FAILED',
    auto_heal_result VARCHAR(500) COMMENT '自愈结果',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 索引
CREATE INDEX idx_alert_history_rule_id ON alert_history(rule_id);
CREATE INDEX idx_alert_history_server_id ON alert_history(server_id);
CREATE INDEX idx_alert_history_level ON alert_history(level);
CREATE INDEX idx_alert_history_status ON alert_history(status);
CREATE INDEX idx_alert_history_create_time ON alert_history(create_time);

-- ============================================================================
-- 模拟日志表（DefaultLogProvider 演示数据）
-- ============================================================================
CREATE TABLE IF NOT EXISTS sys_log_simulate (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    server_id BIGINT COMMENT '服务器ID',
    server_ip VARCHAR(45),
    log_level VARCHAR(20) DEFAULT 'INFO' COMMENT 'INFO/WARN/ERROR',
    log_source VARCHAR(100) COMMENT '来源: nginx/tomcat/system',
    message TEXT NOT NULL COMMENT '日志内容',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- AI诊断历史表
CREATE TABLE IF NOT EXISTS ai_diagnosis_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    alert_id BIGINT COMMENT '关联告警ID',
    server_id BIGINT COMMENT '服务器ID',
    server_ip VARCHAR(45),
    target_type VARCHAR(20) DEFAULT 'SERVER' COMMENT 'SERVER/MIDDLEWARE/APP/CUSTOM',
    target_id BIGINT COMMENT '诊断目标ID',
    target_name VARCHAR(200) COMMENT '诊断目标名称',
    diagnosis_type VARCHAR(20) DEFAULT 'RULE' COMMENT 'RULE-规则引擎 LLM-大模型',
    input_data TEXT COMMENT '诊断输入（指标+日志JSON）',
    diagnosis_result TEXT COMMENT '诊断结果',
    suggestion TEXT COMMENT '修复建议',
    confidence DOUBLE DEFAULT 0 COMMENT '置信度 0-1',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
-- 兼容旧库：诊断对象扩展（服务器/中间件/应用/自定义）
ALTER TABLE ai_diagnosis_history ADD COLUMN target_type VARCHAR(20) DEFAULT 'SERVER';
ALTER TABLE ai_diagnosis_history ADD COLUMN target_id BIGINT;
ALTER TABLE ai_diagnosis_history ADD COLUMN target_name VARCHAR(200);

-- 操作审计日志表
CREATE TABLE IF NOT EXISTS sys_audit_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) COMMENT '操作用户',
    ip_address VARCHAR(45) COMMENT '请求IP',
    request_url VARCHAR(500) COMMENT '请求URL',
    http_method VARCHAR(10) COMMENT 'GET/POST/PUT/DELETE',
    request_params TEXT COMMENT '请求参数（脱敏后）',
    response_status INT COMMENT '状态码',
    duration_ms BIGINT COMMENT '耗时(ms)',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 系统配置表（动态设置持久化）
CREATE TABLE IF NOT EXISTS sys_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    config_key VARCHAR(100) NOT NULL UNIQUE COMMENT '配置键',
    config_value VARCHAR(2000) COMMENT '配置值',
    config_group VARCHAR(50) COMMENT '分组: AI/METRICS/LOG/MAIL',
    description VARCHAR(500) COMMENT '描述',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================================
-- 第8批：中间件资产表
-- ============================================================================
CREATE TABLE IF NOT EXISTS middleware_asset (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL COMMENT '实例名',
    type VARCHAR(30) NOT NULL COMMENT 'KAFKA/NACOS/REDIS/ZOOKEEPER/RABBITMQ/MYSQL/ORACLE/MONGODB/DAMENG/GREENPLUM/GBASE/ELASTICSEARCH',
    version VARCHAR(50) COMMENT '版本号',
    ip VARCHAR(45) COMMENT '连接地址IP',
    port INT COMMENT '端口',
    cluster_name VARCHAR(100) COMMENT '所属集群',
    env VARCHAR(20) DEFAULT 'production' COMMENT 'production/staging/test/dev',
    status VARCHAR(20) DEFAULT 'OFFLINE' COMMENT 'ONLINE/OFFLINE（由端口探测自动维护）',
    server_id BIGINT COMMENT '宿主服务器ID',
    app_ids TEXT COMMENT '关联应用ID列表 JSON',
    tags VARCHAR(500) COMMENT '标签 JSON',
    db_username VARCHAR(50) COMMENT '数据库账号（数据库类资产）',
    db_password_encrypted VARCHAR(500) COMMENT '数据库密码 AES 密文',
    db_name VARCHAR(100) COMMENT '默认数据库名/Schema/SID',
    node_list TEXT COMMENT '集群节点列表（host:port，逗号/换行分隔）',
    console_url VARCHAR(500) COMMENT '管理控制台地址',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 应用资产表
CREATE TABLE IF NOT EXISTS app_asset (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL COMMENT '应用名',
    code VARCHAR(100) NOT NULL UNIQUE COMMENT '应用编码',
    type VARCHAR(30) DEFAULT 'API' COMMENT 'WEB/JOB/API/GATEWAY',
    version VARCHAR(50) COMMENT '当前版本',
    language VARCHAR(30) COMMENT 'Java/Python/Go等',
    deploy_type VARCHAR(30) COMMENT 'JAR/WAR/Docker/K8s',
    env VARCHAR(20) DEFAULT 'production' COMMENT '环境',
    status VARCHAR(20) DEFAULT 'RUNNING' COMMENT 'RUNNING/STOPPED/ERROR',
    server_id BIGINT COMMENT '部署服务器ID',
    depends_middleware_ids TEXT COMMENT '依赖中间件ID JSON数组',
    depends_app_ids TEXT COMMENT '依赖应用ID JSON数组',
    owner VARCHAR(50) COMMENT '负责人',
    tags VARCHAR(500) COMMENT '标签',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 命令模板表
CREATE TABLE IF NOT EXISTS command_template (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL COMMENT '命令名',
    target_type VARCHAR(20) NOT NULL COMMENT 'SERVER/MIDDLEWARE/APP',
    middleware_type VARCHAR(30) COMMENT '限定中间件类型(可空)',
    command VARCHAR(1000) NOT NULL COMMENT '命令内容',
    description VARCHAR(500) COMMENT '描述',
    dangerous INT DEFAULT 0 COMMENT '0-普通 1-危险需二次确认',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 命令执行记录表
CREATE TABLE IF NOT EXISTS exec_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    operator VARCHAR(50) COMMENT '操作人',
    target_type VARCHAR(20) COMMENT 'SERVER/MIDDLEWARE/APP',
    target_id BIGINT COMMENT '目标ID',
    target_ip VARCHAR(45) COMMENT '目标IP',
    command VARCHAR(1000) COMMENT '执行的命令',
    result TEXT COMMENT '执行结果',
    success INT COMMENT '0-失败 1-成功',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_middleware_type ON middleware_asset(type);
-- 兼容旧库：补充 Prometheus 探针列
ALTER TABLE middleware_asset ADD COLUMN exporter_url VARCHAR(500);
ALTER TABLE middleware_asset ADD COLUMN prometheus_job_name VARCHAR(100);
ALTER TABLE middleware_asset ADD COLUMN monitor_enabled INT DEFAULT 0;
-- 兼容旧库：数据库类资产的连接凭据（SQL 工作台连接由此同步）
ALTER TABLE middleware_asset ADD COLUMN db_username VARCHAR(50);
ALTER TABLE middleware_asset ADD COLUMN db_password_encrypted VARCHAR(500);
ALTER TABLE middleware_asset ADD COLUMN db_name VARCHAR(100);
-- 兼容旧库：中间件详情页（节点列表 / 管理控制台）
ALTER TABLE middleware_asset ADD COLUMN node_list TEXT;
ALTER TABLE middleware_asset ADD COLUMN console_url VARCHAR(500);
-- 状态统一由端口探测维护；历史 UNKNOWN 归为 OFFLINE，页面探测后会自动纠正
UPDATE middleware_asset SET status='OFFLINE' WHERE status IS NULL OR status='UNKNOWN';
CREATE INDEX idx_app_code ON app_asset(code);
CREATE INDEX idx_app_env ON app_asset(env);

-- ============================================================================
-- 第9批：AI Copilot 会话/消息/规则模板
-- ============================================================================
CREATE TABLE IF NOT EXISTS ai_chat_session (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT COMMENT '用户ID',
    username VARCHAR(50) COMMENT '用户名',
    title VARCHAR(200) COMMENT '会话标题(首条消息前20字)',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS ai_chat_message (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT NOT NULL COMMENT '会话ID',
    role VARCHAR(20) NOT NULL COMMENT 'user/assistant',
    content TEXT COMMENT '消息内容',
    metadata TEXT COMMENT '结构化方案JSON(steps/codeBlocks/relatedAssets等)',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS ai_solution_template (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    keywords VARCHAR(500) NOT NULL COMMENT '逗号分隔关键词',
    title VARCHAR(200) NOT NULL COMMENT '方案标题',
    summary VARCHAR(500) COMMENT '一句话概述',
    steps TEXT COMMENT '步骤 JSON 数组',
    code_blocks TEXT COMMENT '代码块 JSON 数组 [{language,label,code}]',
    risk_level VARCHAR(10) DEFAULT 'LOW' COMMENT 'LOW/MEDIUM/HIGH',
    risk_tip VARCHAR(500) COMMENT '风险提示',
    navigation TEXT COMMENT '跳转建议 JSON {target,params,label,reason}'
);
-- 兼容旧库：补充 navigation 列
ALTER TABLE ai_solution_template ADD COLUMN navigation TEXT;

CREATE INDEX idx_chat_msg_session ON ai_chat_message(session_id);
CREATE INDEX idx_chat_session_user ON ai_chat_session(user_id);

-- ============================================================================
-- 第10批：资产关系表（拓扑连线，用户手动配置）
-- ============================================================================
CREATE TABLE IF NOT EXISTS asset_relation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    source_type VARCHAR(20) NOT NULL COMMENT 'SERVER/MIDDLEWARE/APP',
    source_id BIGINT NOT NULL,
    target_type VARCHAR(20) NOT NULL,
    target_id BIGINT NOT NULL,
    relation_type VARCHAR(30) NOT NULL COMMENT 'DEPLOY_ON/DEPENDS/CALLS/CONNECTS_TO',
    label VARCHAR(50) COMMENT '连线标签',
    description VARCHAR(255) COMMENT '备注',
    line_style VARCHAR(20) DEFAULT 'solid' COMMENT 'solid/dashed',
    created_by BIGINT COMMENT '创建人用户ID',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_relation UNIQUE (source_type, source_id, target_type, target_id, relation_type)
);

CREATE INDEX idx_relation_source ON asset_relation(source_type, source_id);

-- ============================================================================
-- 第15批：知识库 RAG
-- ============================================================================
CREATE TABLE IF NOT EXISTS kb_knowledge_base (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    embedding_model VARCHAR(50) DEFAULT 'keyword',
    chunk_size INT DEFAULT 500,
    chunk_overlap INT DEFAULT 50,
    doc_count INT DEFAULT 0,
    create_by VARCHAR(50),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS kb_document (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    kb_id BIGINT NOT NULL,
    title VARCHAR(200),
    file_name VARCHAR(200),
    file_type VARCHAR(10),
    file_path VARCHAR(500),
    file_size BIGINT,
    tags VARCHAR(500),
    source VARCHAR(20) DEFAULT 'UPLOAD',
    status VARCHAR(20) DEFAULT 'PENDING',
    error_msg VARCHAR(500),
    chunk_count INT DEFAULT 0,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS kb_chunk (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    doc_id BIGINT NOT NULL,
    kb_id BIGINT NOT NULL,
    chunk_index INT DEFAULT 0,
    content TEXT NOT NULL,
    heading_path VARCHAR(500),
    tokens INT DEFAULT 0,
    embedding TEXT,
    embedding_status VARCHAR(20) DEFAULT 'PENDING',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_kb_doc_kb ON kb_document(kb_id);
CREATE INDEX idx_kb_chunk_kb ON kb_chunk(kb_id);
CREATE INDEX idx_kb_chunk_doc ON kb_chunk(doc_id);

-- ============================================================================
-- 第16批模块二：SQL工作台连接管理
-- ============================================================================
CREATE TABLE IF NOT EXISTS db_connection (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    db_type VARCHAR(30) NOT NULL COMMENT 'MYSQL/ORACLE/DAMENG/GREENPLUM/GBASE',
    host VARCHAR(100) NOT NULL,
    port INT NOT NULL,
    database_name VARCHAR(100),
    username VARCHAR(50),
    password_encrypted VARCHAR(500) COMMENT 'AES加密',
    middleware_id BIGINT COMMENT '关联中间件ID',
    status VARCHAR(20) DEFAULT 'DISCONNECTED',
    last_connect_time TIMESTAMP,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS db_execute_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    connection_id BIGINT NOT NULL,
    sql_text TEXT NOT NULL,
    success INT COMMENT '0-失败 1-成功',
    row_count INT,
    elapsed_ms BIGINT,
    error_msg VARCHAR(500),
    operator VARCHAR(50),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_db_conn_name ON db_connection(name);
CREATE INDEX idx_db_exec_conn ON db_execute_log(connection_id);

-- ============================================================================
-- 第16批模块四：ELK远程管理
-- ============================================================================
CREATE TABLE IF NOT EXISTS elk_connection (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    es_url VARCHAR(200) NOT NULL COMMENT 'Elasticsearch地址',
    es_username VARCHAR(50),
    es_password_encrypted VARCHAR(500),
    logstash_url VARCHAR(200) COMMENT 'Logstash API地址',
    status VARCHAR(20) DEFAULT 'DISCONNECTED',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_elk_conn_name ON elk_connection(name);

-- 索引
CREATE INDEX idx_alert_history_rule_id ON alert_history(rule_id);
CREATE INDEX idx_alert_history_server_id ON alert_history(server_id);

-- ============================================================================
-- 第17批：项目管理（多对多关联服务器/中间件/应用）
-- ============================================================================
CREATE TABLE IF NOT EXISTS project (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL COMMENT '项目名称',
    code VARCHAR(50) NOT NULL COMMENT '项目编码（唯一）',
    owner VARCHAR(50) COMMENT '负责人',
    description VARCHAR(500) COMMENT '项目描述',
    status VARCHAR(20) DEFAULT 'ACTIVE' COMMENT 'ACTIVE/INACTIVE',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_project_code UNIQUE (code)
);

CREATE TABLE IF NOT EXISTS project_asset (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT NOT NULL COMMENT '项目ID',
    asset_type VARCHAR(20) NOT NULL COMMENT 'SERVER/MIDDLEWARE/APP',
    asset_id BIGINT NOT NULL COMMENT '资产ID',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_project_asset UNIQUE (project_id, asset_type, asset_id)
);
-- ============================================================================
-- 第18批：命令执行历史（批量/流式执行记录）
-- ============================================================================
CREATE TABLE IF NOT EXISTS exec_run (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64),
    operator VARCHAR(50),
    command TEXT,
    server_count INT DEFAULT 0,
    success_count INT DEFAULT 0,
    fail_count INT DEFAULT 0,
    dry_run INT DEFAULT 0,
    risk_level VARCHAR(20),
    status VARCHAR(20) DEFAULT 'RUNNING',
    start_time TIMESTAMP,
    end_time TIMESTAMP,
    duration_ms BIGINT,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS exec_run_host (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id BIGINT NOT NULL,
    server_id BIGINT,
    hostname VARCHAR(100),
    ip VARCHAR(45),
    environment VARCHAR(20),
    exit_code INT,
    success INT,
    duration_ms BIGINT,
    stdout TEXT,
    stderr TEXT,
    error_msg VARCHAR(1000),
    killed INT DEFAULT 0,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_exec_run_request ON exec_run(request_id);
CREATE INDEX idx_exec_run_time ON exec_run(create_time);
CREATE INDEX idx_exec_run_host_run ON exec_run_host(run_id);

-- ============================================================================
-- 第19批：项目-角色可见范围（不同角色在不同项目看到不同服务器）
-- ============================================================================
CREATE TABLE IF NOT EXISTS project_role (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_project_role UNIQUE (project_id, role_id)
);

-- ============================================================================
-- 第20批：指标注册中心（Metric Catalog）+ 推送样本
-- ============================================================================
CREATE TABLE IF NOT EXISTS metric_definition (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(100) NOT NULL UNIQUE COMMENT '指标唯一编码，如 node_cpu_usage',
    name VARCHAR(100) NOT NULL COMMENT '指标名称',
    description VARCHAR(500) COMMENT '说明',
    source_type VARCHAR(20) NOT NULL DEFAULT 'prometheus' COMMENT 'prometheus/http_json/push',
    query_expr TEXT COMMENT 'PromQL 或 HTTP URL',
    config_json TEXT COMMENT 'http_json 配置: {"method":"GET","headers":{},"jsonPath":"data.value"}',
    labels_json VARCHAR(1000) COMMENT 'JSON 数组，如 ["instance","app"]',
    unit VARCHAR(20) DEFAULT 'num' COMMENT 'pct/bps/bytes/num/ms/count',
    value_type VARCHAR(20) DEFAULT 'gauge' COMMENT 'gauge/counter',
    scope_type VARCHAR(20) DEFAULT 'GLOBAL' COMMENT 'GLOBAL/PROJECT/ASSET/MIDDLEWARE/APP',
    scope_id BIGINT COMMENT '范围 ID',
    thresholds_json VARCHAR(1000) COMMENT '告警默认阈值 {"warning":75,"critical":90}',
    builtin INT DEFAULT 0 COMMENT '1-内置指标',
    enabled INT DEFAULT 1,
    owner VARCHAR(50),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS metric_sample (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    metric_code VARCHAR(100) NOT NULL,
    labels_json VARCHAR(1000) COMMENT 'JSON 对象，如 {"app":"order","env":"prod"}',
    metric_value DOUBLE NOT NULL,
    sample_time BIGINT NOT NULL COMMENT 'epoch 毫秒',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_metric_sample_code_time ON metric_sample(metric_code, sample_time);

-- ============================================================================
-- 第21批：Agent/Exporter 安装记录 + FTP 资产
-- ============================================================================
CREATE TABLE IF NOT EXISTS server_agent (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    server_id BIGINT NOT NULL,
    exporter VARCHAR(50) NOT NULL,
    service_name VARCHAR(100),
    port INT,
    install_dir VARCHAR(300),
    package_name VARCHAR(200),
    version VARCHAR(50),
    status VARCHAR(20) DEFAULT 'UNKNOWN',
    message VARCHAR(2000),
    installed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_server_agent UNIQUE (server_id, exporter)
);
-- 兼容旧库：Exporter 安装版本
ALTER TABLE server_agent ADD COLUMN version VARCHAR(50);
-- 兼容旧库：服务器/中间件资产标签
ALTER TABLE server_asset ADD COLUMN tags VARCHAR(1000);

CREATE TABLE IF NOT EXISTS ftp_server (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    protocol VARCHAR(20) DEFAULT 'FTP' COMMENT 'FTP/FTPS/SFTP',
    host VARCHAR(255) NOT NULL,
    port INT DEFAULT 21,
    username VARCHAR(100),
    password_encrypted VARCHAR(500),
    base_path VARCHAR(500),
    passive_mode INT DEFAULT 1,
    enabled INT DEFAULT 1,
    description VARCHAR(500),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
