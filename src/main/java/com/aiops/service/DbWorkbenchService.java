package com.aiops.service;

import com.aiops.entity.DbConnection;
import com.aiops.entity.DbExecuteLog;
import com.aiops.entity.MiddlewareAsset;
import com.aiops.repository.DbConnectionRepository;
import com.aiops.repository.DbExecuteLogRepository;
import com.aiops.repository.MiddlewareAssetRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 数据库SQL工作台服务
 * 动态创建JDBC连接（复用已有驱动），执行SQL，查询表结构。
 * 密码AES加密存储，明文不出网。
 */
@Service
@RequiredArgsConstructor
public class DbWorkbenchService {

    private static final Logger log = LoggerFactory.getLogger(DbWorkbenchService.class);

    private final DbConnectionRepository connRepo;
    private final DbExecuteLogRepository execLogRepo;
    private final MiddlewareAssetRepository middlewareRepo;
    private final PasswordEncryptor passwordEncryptor;

    /** SQL 工作台支持的数据库类型（资产类型 -> JDBC） */
    private static final Set<String> WORKBENCH_DB_TYPES = new HashSet<String>(Arrays.asList(
            "MYSQL", "POSTGRESQL", "GREENPLUM", "GBASE", "ORACLE", "DAMENG"));

    // ==================== 资产 → 连接同步 ====================

    /**
     * 以“资产管理 → 数据库类”为唯一数据源同步 SQL 工作台连接：
     * - 资产的 名称/IP/端口/账号/密码/默认库 变更后，下次同步即生效
     * - 历史手工连接若未关联资产，会自动迁移为一条数据库资产（保留原加密密码），避免升级后连接丢失
     * - 资产删除或改为非数据库类型时，对应连接一并删除
     */
    @Transactional
    public Map<String, Object> syncFromMiddleware() {
        List<MiddlewareAsset> assets = new ArrayList<MiddlewareAsset>(middlewareRepo.findAll());
        int migrated = 0, created = 0, updated = 0, removed = 0, skipped = 0;
        int passwordUpdated = 0, backfilled = 0;

        // 1) 历史手工连接 → 资产
        for (DbConnection c : connRepo.findAll()) {
            if (c.getMiddlewareId() != null) continue;
            if (!WORKBENCH_DB_TYPES.contains(c.getDbType())) continue;
            if (isBlank(c.getHost())) continue;
            MiddlewareAsset matched = findMatchingAsset(assets, c);
            if (matched == null) {
                matched = new MiddlewareAsset();
                matched.setName(isBlank(c.getName()) ? c.getHost() : c.getName());
                matched.setType(c.getDbType());
                matched.setIp(c.getHost().trim());
                matched.setPort(c.getPort());
                matched.setDbName(trimToNull(c.getDatabaseName()));
                matched.setDbUsername(trimToNull(c.getUsername()));
                matched.setDbPasswordEncrypted(c.getPasswordEncrypted());
                matched.setEnv("production");
                matched.setStatus("UNKNOWN");
                matched.setMonitorEnabled(0);
                matched = middlewareRepo.save(matched);
                assets.add(matched);
            } else {
                // 历史连接已能匹配到资产：把连接里有、资产里缺的凭据补回资产，
                // 否则第 2 步会用资产的空密码把连接密码覆盖成 NULL（历史密码丢失）。
                boolean filled = false;
                if (isBlank(matched.getDbUsername()) && !isBlank(c.getUsername())) {
                    matched.setDbUsername(c.getUsername().trim());
                    filled = true;
                }
                if (isBlank(matched.getDbName()) && !isBlank(c.getDatabaseName())) {
                    matched.setDbName(c.getDatabaseName().trim());
                    filled = true;
                }
                if (isBlank(matched.getDbPasswordEncrypted()) && !isBlank(c.getPasswordEncrypted())) {
                    matched.setDbPasswordEncrypted(c.getPasswordEncrypted());
                    filled = true;
                    backfilled++;
                }
                if (filled) matched = middlewareRepo.save(matched);
            }
            c.setMiddlewareId(matched.getId());
            connRepo.save(c);
            migrated++;
        }

        // 2) 资产 → 连接（增/改）
        Map<Long, DbConnection> byMiddleware = new HashMap<Long, DbConnection>();
        for (DbConnection c : connRepo.findAll()) {
            if (c.getMiddlewareId() != null) byMiddleware.put(c.getMiddlewareId(), c);
        }
        for (MiddlewareAsset m : assets) {
            if (!WORKBENCH_DB_TYPES.contains(m.getType())) continue;
            if (isBlank(m.getIp())) { skipped++; continue; }
            DbConnection c = byMiddleware.get(m.getId());
            boolean isNew = c == null;
            if (isNew) {
                c = new DbConnection();
                c.setMiddlewareId(m.getId());
                c.setStatus("DISCONNECTED");
            }
            // 资产没有密码但连接里已有密码（早期手工连接/历史数据）：保留连接密码并回填到资产，
            // 避免每次同步都用 NULL 覆盖已有密码。
            if (isBlank(m.getDbPasswordEncrypted())
                    && c.getPasswordEncrypted() != null && !c.getPasswordEncrypted().isEmpty()) {
                m.setDbPasswordEncrypted(c.getPasswordEncrypted());
                middlewareRepo.save(m);
                backfilled++;
                log.info("SQL工作台同步: 资产[{}]未配置密码，已从连接[{}]回填", m.getId(), c.getId());
            }
            String oldPwd = c.getPasswordEncrypted();
            boolean changed = applyAsset(c, m);
            if (!Objects.equals(oldPwd, c.getPasswordEncrypted())) {
                passwordUpdated++;
                if (oldPwd != null) {
                    log.info("SQL工作台同步: 连接[{}]密码已按资产[{}]更新", c.getId(), m.getId());
                }
            }
            if (isNew) { connRepo.save(c); created++; }
            else if (changed) { connRepo.save(c); updated++; }
        }

        // 3) 清理：资产已删除或不再是数据库类型
        Set<Long> valid = new HashSet<Long>();
        for (MiddlewareAsset m : assets) {
            if (WORKBENCH_DB_TYPES.contains(m.getType()) && !isBlank(m.getIp())) valid.add(m.getId());
        }
        for (DbConnection c : connRepo.findAll()) {
            if (c.getMiddlewareId() == null || valid.contains(c.getMiddlewareId())) continue;
            execLogRepo.deleteByConnectionId(c.getId());
            connRepo.delete(c);
            removed++;
        }

        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("assets", assets.size());
        summary.put("migrated", migrated);
        summary.put("created", created);
        summary.put("updated", updated);
        summary.put("removed", removed);
        summary.put("skipped", skipped);
        summary.put("passwordUpdated", passwordUpdated);
        summary.put("backfilled", backfilled);
        log.info("SQL工作台连接同步完成: {}", summary);
        return summary;
    }

    private MiddlewareAsset findMatchingAsset(List<MiddlewareAsset> assets, DbConnection c) {
        for (MiddlewareAsset m : assets) {
            if (!WORKBENCH_DB_TYPES.contains(m.getType())) continue;
            if (!same(m.getIp(), c.getHost())) continue;
            if (!Objects.equals(m.getPort(), c.getPort())) continue;
            if (!same(m.getType(), c.getDbType())) continue;
            return m;
        }
        return null;
    }

    /** 把资产字段写回连接，返回是否有变化 */
    private boolean applyAsset(DbConnection c, MiddlewareAsset m) {
        boolean changed = false;
        String name = trimToNull(m.getName());
        String type = trimToNull(m.getType());
        String host = trimToNull(m.getIp());
        Integer port = m.getPort() != null && m.getPort() > 0 ? m.getPort() : defaultPort(m.getType());
        String dbName = trimToNull(m.getDbName());
        String user = trimToNull(m.getDbUsername());
        String pwd = m.getDbPasswordEncrypted();

        if (!same(c.getName(), name)) { c.setName(name); changed = true; }
        if (!same(c.getDbType(), type)) { c.setDbType(type); changed = true; }
        if (!same(c.getHost(), host)) { c.setHost(host); changed = true; }
        if (!Objects.equals(c.getPort(), port)) { c.setPort(port); changed = true; }
        if (!same(c.getDatabaseName(), dbName)) { c.setDatabaseName(dbName); changed = true; }
        if (!same(c.getUsername(), user)) { c.setUsername(user); changed = true; }
        if (!Objects.equals(c.getPasswordEncrypted(), pwd)) { c.setPasswordEncrypted(pwd); changed = true; }
        return changed;
    }

    private boolean same(String a, String b) {
        return Objects.equals(trimToNull(a), trimToNull(b));
    }

    private String trimToNull(String s) {
        if (s == null) return null;
        String v = s.trim();
        return v.isEmpty() ? null : v;
    }

    private boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }

    /** 资产删除时同步删除其工作台连接与执行历史 */
    @Transactional
    public void removeByMiddlewareId(Long middlewareId) {
        for (DbConnection c : connRepo.findByMiddlewareId(middlewareId)) {
            execLogRepo.deleteByConnectionId(c.getId());
            connRepo.delete(c);
        }
    }

    private int defaultPort(String dbType) {
        if ("MYSQL".equals(dbType)) return 3306;
        if ("POSTGRESQL".equals(dbType) || "GREENPLUM".equals(dbType) || "GBASE".equals(dbType)) return 5432;
        if ("ORACLE".equals(dbType)) return 1521;
        if ("DAMENG".equals(dbType)) return 5236;
        return 0;
    }

    public List<DbConnection> listConnections() {
        List<DbConnection> result = new ArrayList<DbConnection>();
        for (DbConnection c : connRepo.findAllByOrderByIdDesc()) {
            result.add(toSafe(c));
        }
        return result;
    }

    public DbConnection getConnection(Long id) {
        return connRepo.findById(id).orElse(null);
    }

    /**
     * 返回脱敏副本（不修改传入的持久化实体）。
     * 直接把 passwordEncrypted 置空会触发 JPA 脏检查，把 NULL 写回数据库。
     */
    private DbConnection toSafe(DbConnection src) {
        if (src == null) return null;
        DbConnection c = new DbConnection();
        c.setId(src.getId());
        c.setName(src.getName());
        c.setDbType(src.getDbType());
        c.setHost(src.getHost());
        c.setPort(src.getPort());
        c.setDatabaseName(src.getDatabaseName());
        c.setUsername(src.getUsername());
        c.setMiddlewareId(src.getMiddlewareId());
        c.setStatus(src.getStatus());
        c.setLastConnectTime(src.getLastConnectTime());
        c.setCreateTime(src.getCreateTime());
        c.setUpdateTime(src.getUpdateTime());
        c.setPasswordSet(src.getPasswordEncrypted() != null && !src.getPasswordEncrypted().isEmpty());
        return c;
    }

    /** 解密真实密码 */
    private String decryptPassword(DbConnection conn) {
        String enc = conn.getPasswordEncrypted();
        if (enc != null && !enc.isEmpty()) return passwordEncryptor.decrypt(enc);
        return null;
    }

    // ==================== 连通性测试 ====================

    /** 测试连通性，返回 success/message/elapsedMs */
    @Transactional
    public Map<String, Object> testConnection(Long id) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        DbConnection conn = getConnection(id);
        if (conn == null) {
            result.put("success", false);
            result.put("message", "连接不存在");
            return result;
        }
        long start = System.currentTimeMillis();
        try (Connection jdbc = createJdbcConnection(conn); Statement st = jdbc.createStatement()) {
            st.setQueryTimeout(10);
            st.execute("SELECT 1");
            conn.setStatus("CONNECTED");
            conn.setLastConnectTime(LocalDateTime.now());
            connRepo.save(conn);
            result.put("success", true);
            result.put("message", "连接成功");
        } catch (Exception e) {
            conn.setStatus("ERROR");
            connRepo.save(conn);
            result.put("success", false);
            result.put("message", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            log.warn("DB连接测试失败: {}", e.getMessage());
        }
        result.put("elapsedMs", System.currentTimeMillis() - start);
        return result;
    }

    /** 构造 JDBC URL */
    private String buildJdbcUrl(DbConnection conn) {
        String host = conn.getHost();
        int port = conn.getPort() == null ? 0 : conn.getPort();
        String db = conn.getDatabaseName() != null ? conn.getDatabaseName().trim() : "";
        switch (conn.getDbType()) {
            case "MYSQL":
                return "jdbc:mysql://" + host + ":" + port + "/" + db + "?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";
            case "POSTGRESQL":
            case "GREENPLUM":
            case "GBASE":
                return "jdbc:postgresql://" + host + ":" + port + "/" + db;
            case "ORACLE":
                return "jdbc:oracle:thin:@" + host + ":" + port + ":" + db;
            case "DAMENG":
                return "jdbc:dm://" + host + ":" + port + "?schema=" + db;
            case "H2":
                String path = db.isEmpty() ? "./data/sqltest" : db;
                if (path.startsWith("mem:") || path.startsWith("file:")) {
                    return "jdbc:h2:" + path + (path.contains(";") ? "" : ";MODE=MySQL");
                }
                return "jdbc:h2:file:" + path + (path.contains(";") ? "" : ";MODE=MySQL");
            default:
                throw new com.aiops.common.ServiceException("不支持的数据库类型: " + conn.getDbType());
        }
    }

    private Connection createJdbcConnection(DbConnection conn) throws SQLException {
        String url = buildJdbcUrl(conn);
        String user = conn.getUsername();
        String pwd = decryptPassword(conn);
        if ("ORACLE".equals(conn.getDbType())) {
            return java.sql.DriverManager.getConnection(url, user, pwd);
        }
        return java.sql.DriverManager.getConnection(url, user, pwd);
    }

    // ==================== SQL 执行 ====================

    @Transactional
    public Map<String, Object> executeSql(Long connectionId, String sql, int maxRows, String operator) {
        return executeSql(connectionId, sql, maxRows, operator, null);
    }

    /**
     * 执行 SQL，返回结果集（查询）或受影响行数（DML/DDL）
     * database 为空时使用连接配置中的数据库（MySQL）。
     */
    @Transactional
    public Map<String, Object> executeSql(Long connectionId, String sql, int maxRows, String operator, String database) {
        DbConnection conn = getConnection(connectionId);
        if (conn == null) throw new com.aiops.common.ServiceException("连接不存在");
        if (sql == null || sql.trim().isEmpty()) throw new com.aiops.common.ServiceException("SQL不能为空");
        if (maxRows <= 0) maxRows = 500;
        if (maxRows > 5000) maxRows = 5000;
        final int limit = maxRows;

        long start = System.currentTimeMillis();
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        DbExecuteLog execLog = new DbExecuteLog();
        execLog.setConnectionId(connectionId);
        execLog.setSqlText(sql);
        execLog.setOperator(operator);

        try (Connection jdbc = createJdbcConnection(conn)) {
            applyDatabase(jdbc, conn, database);
            boolean readOnly = sql.trim().toUpperCase().startsWith("SELECT")
                    || sql.trim().toUpperCase().startsWith("SHOW")
                    || sql.trim().toUpperCase().startsWith("DESC")
                    || sql.trim().toUpperCase().startsWith("EXPLAIN");
            try { jdbc.setReadOnly(readOnly); } catch (Exception ignore) { /* 部分驱动不支持 */ }

            try (Statement st = jdbc.createStatement()) {
                st.setMaxRows(limit);
                st.setQueryTimeout(60);

                boolean isQuery = st.execute(sql);

                if (isQuery) {
                    try (ResultSet rs = st.getResultSet()) {
                        ResultSetMetaData meta = rs.getMetaData();
                        int colCount = meta.getColumnCount();
                        List<String> columns = new ArrayList<String>();
                        for (int i = 1; i <= colCount; i++) columns.add(meta.getColumnLabel(i));

                        List<List<Object>> rows = new ArrayList<List<Object>>();
                        while (rs.next() && rows.size() < limit) {
                            List<Object> row = new ArrayList<Object>();
                            for (int i = 1; i <= colCount; i++) {
                                Object val = rs.getObject(i);
                                row.add(val != null ? val.toString() : null);
                            }
                            rows.add(row);
                        }

                        result.put("success", true);
                        result.put("columns", columns);
                        result.put("rows", rows);
                        result.put("rowCount", rows.size());
                        result.put("hasMore", rows.size() >= limit);
                        execLog.setSuccess(1);
                        execLog.setRowCount(rows.size());
                    }
                } else {
                    int affected = st.getUpdateCount();
                    result.put("success", true);
                    result.put("affectedRows", affected);
                    result.put("rowCount", affected);
                    execLog.setSuccess(1);
                    execLog.setRowCount(affected);
                }
            }
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (msg.length() > 500) msg = msg.substring(0, 500);
            result.put("success", false);
            result.put("error", msg);
            execLog.setSuccess(0);
            execLog.setErrorMsg(msg);
            log.warn("SQL执行失败: {}", msg);
        }

        if (Boolean.TRUE.equals(result.get("success"))) {
            conn.setStatus("CONNECTED");
            conn.setLastConnectTime(LocalDateTime.now());
            connRepo.save(conn);
        }

        long elapsed = System.currentTimeMillis() - start;
        result.put("elapsedMs", elapsed);
        execLog.setElapsedMs(elapsed);
        execLogRepo.save(execLog);

        return result;
    }

    // ==================== 表结构 ====================

    /** 获取数据库表列表 */
    public List<Map<String, String>> listTables(Long connectionId) throws Exception {
        return listTables(connectionId, null);
    }

    public List<Map<String, String>> listTables(Long connectionId, String database) throws Exception {
        DbConnection conn = getConnection(connectionId);
        if (conn == null) throw new com.aiops.common.ServiceException("连接不存在");
        List<Map<String, String>> tables = new ArrayList<Map<String, String>>();
        try (Connection jdbc = createJdbcConnection(conn)) {
            applyDatabase(jdbc, conn, database);
            DatabaseMetaData meta = jdbc.getMetaData();
            String[] cs = catalogAndSchema(jdbc, conn);
            // MySQL 未选择数据库时返回空，由前端提示选择
            if ("MYSQL".equals(conn.getDbType()) && (cs[0] == null || cs[0].trim().isEmpty())) {
                return tables;
            }
            try (ResultSet rs = meta.getTables(cs[0], cs[1], "%", new String[]{"TABLE", "VIEW"})) {
                while (rs.next()) {
                    Map<String, String> t = new LinkedHashMap<String, String>();
                    t.put("name", rs.getString("TABLE_NAME"));
                    t.put("type", rs.getString("TABLE_TYPE"));
                    t.put("remarks", rs.getString("REMARKS"));
                    tables.add(t);
                }
            }
        }
        return tables;
    }

    /** 获取数据库/schema 列表，用于工作台数据库下拉 */
    public List<String> listDatabases(Long connectionId) throws Exception {
        DbConnection conn = getConnection(connectionId);
        if (conn == null) throw new com.aiops.common.ServiceException("连接不存在");
        List<String> result = new ArrayList<String>();
        try (Connection jdbc = createJdbcConnection(conn)) {
            DatabaseMetaData meta = jdbc.getMetaData();
            if ("MYSQL".equals(conn.getDbType())) {
                try (ResultSet rs = meta.getCatalogs()) {
                    while (rs.next()) {
                        String name = rs.getString("TABLE_CAT");
                        if (isSystemSchema(name)) continue;
                        result.add(name);
                    }
                }
            } else {
                try (ResultSet rs = meta.getSchemas()) {
                    while (rs.next()) {
                        String name = rs.getString("TABLE_SCHEM");
                        if (isSystemSchema(name)) continue;
                        result.add(name);
                    }
                }
            }
        }
        if (result.isEmpty() && conn.getDatabaseName() != null && !conn.getDatabaseName().trim().isEmpty()) {
            result.add(conn.getDatabaseName().trim());
        }
        Collections.sort(result);
        return result;
    }

    private boolean isSystemSchema(String name) {
        if (name == null) return true;
        String n = name.toLowerCase();
        return n.equals("information_schema") || n.equals("mysql") || n.equals("performance_schema")
                || n.equals("sys") || n.startsWith("pg_") || n.equals("pg_catalog")
                || n.equals("pg_toast") || n.equals("sysaux") || n.equals("system")
                || n.equals("outln") || n.equals("xdb") || n.equals("dbsnmp");
    }

    /** 获取表字段结构 */
    public List<Map<String, String>> tableSchema(Long connectionId, String tableName) throws Exception {
        return tableSchema(connectionId, tableName, null);
    }

    public List<Map<String, String>> tableSchema(Long connectionId, String tableName, String database) throws Exception {
        DbConnection conn = getConnection(connectionId);
        if (conn == null) throw new com.aiops.common.ServiceException("连接不存在");
        if (tableName == null || tableName.trim().isEmpty()) {
            throw new com.aiops.common.ServiceException("表名不能为空");
        }
        List<Map<String, String>> columns = new ArrayList<Map<String, String>>();
        try (Connection jdbc = createJdbcConnection(conn)) {
            applyDatabase(jdbc, conn, database);
            DatabaseMetaData meta = jdbc.getMetaData();
            String[] cs = catalogAndSchema(jdbc, conn);
            if ("MYSQL".equals(conn.getDbType()) && (cs[0] == null || cs[0].trim().isEmpty())) {
                throw new com.aiops.common.ServiceException("请先选择数据库");
            }
            try (ResultSet rs = meta.getColumns(cs[0], cs[1], tableName.trim(), "%")) {
                while (rs.next()) {
                    Map<String, String> col = new LinkedHashMap<String, String>();
                    col.put("name", rs.getString("COLUMN_NAME"));
                    col.put("type", rs.getString("TYPE_NAME"));
                    col.put("size", rs.getString("COLUMN_SIZE"));
                    col.put("nullable", "YES".equals(rs.getString("IS_NULLABLE")) ? "NULL" : "NOT NULL");
                    col.put("default", rs.getString("COLUMN_DEF"));
                    col.put("remarks", rs.getString("REMARKS"));
                    columns.add(col);
                }
            }
        }
        return columns;
    }

    /** 切换数据库：MySQL 设置 catalog，其余设置 schema */
    private void applyDatabase(Connection jdbc, DbConnection conn, String database) throws SQLException {
        String db = database != null && !database.trim().isEmpty() ? database.trim() : null;
        if (db == null && "MYSQL".equals(conn.getDbType())) {
            db = conn.getDatabaseName();
        }
        if (db == null || db.isEmpty()) return;
        if ("MYSQL".equals(conn.getDbType())) {
            jdbc.setCatalog(db);
        } else {
            jdbc.setSchema(db);
        }
    }

    /** 不同数据库的 catalog/schema 定位方式不同 */
    private String[] catalogAndSchema(Connection jdbc, DbConnection conn) {
        String catalog = null;
        String schema = null;
        String type = conn.getDbType();
        try {
            if ("MYSQL".equals(type)) {
                catalog = jdbc.getCatalog();
            } else if ("H2".equals(type)) {
                catalog = jdbc.getCatalog();
                schema = jdbc.getSchema();
                if (schema == null || schema.trim().isEmpty()) schema = "PUBLIC";
            } else if ("POSTGRESQL".equals(type) || "GREENPLUM".equals(type) || "GBASE".equals(type)) {
                schema = jdbc.getSchema();
                if (schema == null || schema.trim().isEmpty()) schema = "public";
            } else if ("ORACLE".equals(type) || "DAMENG".equals(type)) {
                schema = conn.getUsername() != null ? conn.getUsername().toUpperCase() : null;
            }
            if (catalog == null && "MYSQL".equals(type)) {
                catalog = conn.getDatabaseName();
            }
        } catch (Exception e) {
            log.debug("获取catalog失败: {}", e.getMessage());
        }
        return new String[]{catalog, schema};
    }

    // ==================== 执行历史 ====================

    public List<DbExecuteLog> listExecLogs(Long connectionId) {
        return listExecLogs(connectionId, 100);
    }

    public List<DbExecuteLog> listExecLogs(Long connectionId, int limit) {
        if (limit <= 0) limit = 100;
        if (limit > 500) limit = 500;
        List<DbExecuteLog> logs = connectionId == null
                ? execLogRepo.findAllByOrderByIdDesc()
                : execLogRepo.findByConnectionIdOrderByIdDesc(connectionId);
        return logs.size() > limit ? new ArrayList<DbExecuteLog>(logs.subList(0, limit)) : logs;
    }

    // ==================== SQL 安全检查 ====================

    /**
     * 检查SQL是否危险（需要二次确认）
     */
    public String checkDangerous(String sql) {
        if (sql == null) return null;
        String up = sql.toUpperCase().trim();
        if (up.startsWith("DROP") || up.startsWith("TRUNCATE")) {
            return "危险操作：该SQL会删除表或数据，请确认！";
        }
        if (up.startsWith("ALTER")) {
            return "危险操作：该SQL会修改表结构，请确认！";
        }
        if (up.startsWith("DELETE") && !up.contains("WHERE")) {
            return "危险操作：DELETE 无 WHERE 条件，会删除全表数据！";
        }
        if (up.startsWith("UPDATE") && !up.contains("WHERE")) {
            return "危险操作：UPDATE 无 WHERE 条件，会更新全表！";
        }
        return null;
    }
}