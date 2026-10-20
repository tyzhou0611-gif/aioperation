package com.aiops.service;

import com.aiops.entity.DbConnection;
import com.aiops.entity.DbExecuteLog;
import com.aiops.repository.DbConnectionRepository;
import com.aiops.repository.DbExecuteLogRepository;
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
    private final PasswordEncryptor passwordEncryptor;

    // ==================== 连接管理 ====================

    @Transactional
    public DbConnection createConnection(DbConnection conn) {
        String plain = conn.getPassword();
        conn.setPassword(null);
        conn.setPasswordEncrypted(plain != null && !plain.isEmpty() ? passwordEncryptor.encrypt(plain) : null);
        return scrub(connRepo.save(conn));
    }

    public List<DbConnection> listConnections() {
        List<DbConnection> list = connRepo.findAllByOrderByIdDesc();
        list.forEach(this::scrub);
        return list;
    }

    public DbConnection getConnection(Long id) {
        return connRepo.findById(id).orElse(null);
    }

    @Transactional
    public void deleteConnection(Long id) { connRepo.deleteById(id); }

    private DbConnection scrub(DbConnection c) {
        if (c == null) return null;
        c.setPasswordSet(c.getPasswordEncrypted() != null && !c.getPasswordEncrypted().isEmpty());
        c.setPassword(null);
        c.setPasswordEncrypted(null);
        return c;
    }

    /** 解密真实密码 */
    private String decryptPassword(DbConnection conn) {
        String enc = conn.getPasswordEncrypted();
        if (enc != null && !enc.isEmpty()) return passwordEncryptor.decrypt(enc);
        return null;
    }

    // ==================== 连通性测试 ====================

    @Transactional
    public boolean testConnection(Long id) {
        DbConnection conn = getConnection(id);
        if (conn == null) return false;
        try {
            try (Connection jdbc = createJdbcConnection(conn); Statement st = jdbc.createStatement()) {
                st.execute("SELECT 1");
                conn.setStatus("CONNECTED");
                conn.setLastConnectTime(LocalDateTime.now());
                connRepo.save(conn);
                return true;
            }
        } catch (Exception e) {
            log.warn("DB连接测试失败: {}", e.getMessage());
            conn.setStatus("ERROR");
            connRepo.save(conn);
            return false;
        }
    }

    /** 构造 JDBC URL */
    private String buildJdbcUrl(DbConnection conn) {
        String host = conn.getHost();
        int port = conn.getPort();
        String db = conn.getDatabaseName() != null ? conn.getDatabaseName() : "";
        switch (conn.getDbType()) {
            case "MYSQL":      return "jdbc:mysql://" + host + ":" + port + "/" + db + "?useSSL=false&serverTimezone=Asia/Shanghai";
            case "ORACLE":    return "jdbc:oracle:thin:@" + host + ":" + port + ":" + db;
            case "DAMENG":    return "jdbc:dm://" + host + ":" + port + "?schema=" + db;
            case "GREENPLUM":
            case "GBASE":     return "jdbc:postgresql://" + host + ":" + port + "/" + db;
            default:          return "jdbc:mysql://" + host + ":" + port + "/" + db;
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

    /**
     * 执行 SQL，返回结果集（查询）或受影响行数（DML/DDL）
     */
    @Transactional
    public Map<String, Object> executeSql(Long connectionId, String sql, int maxRows, String operator) {
        DbConnection conn = getConnection(connectionId);
        if (conn == null) throw new com.aiops.common.ServiceException("连接不存在");
        if (sql == null || sql.trim().isEmpty()) throw new com.aiops.common.ServiceException("SQL不能为空");

        long start = System.currentTimeMillis();
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        DbExecuteLog execLog = new DbExecuteLog();
        execLog.setConnectionId(connectionId);
        execLog.setSqlText(sql);
        execLog.setOperator(operator);

        try (Connection jdbc = createJdbcConnection(conn)) {
            jdbc.setNetworkTimeout(null, 30000); // 30秒超时
            boolean readOnly = sql.trim().toUpperCase().startsWith("SELECT") || sql.trim().toUpperCase().startsWith("SHOW") || sql.trim().toUpperCase().startsWith("DESC");
            jdbc.setReadOnly(readOnly);

            try (Statement st = jdbc.createStatement()) {
                st.setMaxRows(maxRows > 0 ? maxRows : 1000);
                st.setQueryTimeout(30);

                boolean isQuery = st.execute(sql);

                if (isQuery) {
                    try (ResultSet rs = st.getResultSet()) {
                        ResultSetMetaData meta = rs.getMetaData();
                        int colCount = meta.getColumnCount();
                        List<String> columns = new ArrayList<String>();
                        for (int i = 1; i <= colCount; i++) columns.add(meta.getColumnLabel(i));

                        List<List<Object>> rows = new ArrayList<List<Object>>();
                        while (rs.next() && rows.size() < maxRows) {
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
                        result.put("hasMore", rows.size() >= maxRows);
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
            result.put("success", false);
            result.put("error", e.getMessage());
            execLog.setSuccess(0);
            execLog.setErrorMsg(e.getMessage());
            log.warn("SQL执行失败: {}", e.getMessage());
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
        DbConnection conn = getConnection(connectionId);
        List<Map<String, String>> tables = new ArrayList<Map<String, String>>();
        try (Connection jdbc = createJdbcConnection(conn)) {
            DatabaseMetaData meta = jdbc.getMetaData();
            String catalog = conn.getDatabaseName();
            try (ResultSet rs = meta.getTables(catalog, null, "%", new String[]{"TABLE", "VIEW"})) {
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

    /** 获取表字段结构 */
    public List<Map<String, String>> tableSchema(Long connectionId, String tableName) throws Exception {
        DbConnection conn = getConnection(connectionId);
        List<Map<String, String>> columns = new ArrayList<Map<String, String>>();
        try (Connection jdbc = createJdbcConnection(conn)) {
            DatabaseMetaData meta = jdbc.getMetaData();
            try (ResultSet rs = meta.getColumns(null, null, tableName, "%")) {
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

    // ==================== 执行历史 ====================

    public List<DbExecuteLog> listExecLogs(Long connectionId) {
        return execLogRepo.findByConnectionIdOrderByIdDesc(connectionId);
    }

    // ==================== SQL 安全检查 ====================

    /**
     * 检查SQL是否危险（需要二次确认）
     */
    public String checkDangerous(String sql) {
        if (sql == null) return null;
        String up = sql.toUpperCase().trim();
        if (up.startsWith("DROP") || up.startsWith("TRUNCATE")) {
            return "危险操作：该SQL会删除表数据，请确认！";
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