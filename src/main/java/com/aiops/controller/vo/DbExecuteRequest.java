package com.aiops.controller.vo;

/**
 * SQL 执行请求
 */
public class DbExecuteRequest {

    private Long connectionId;
    private String sql;
    private Integer maxRows;
    private String database;

    public Long getConnectionId() { return connectionId; }
    public void setConnectionId(Long v) { connectionId = v; }
    public String getSql() { return sql; }
    public void setSql(String v) { sql = v; }
    public Integer getMaxRows() { return maxRows; }
    public void setMaxRows(Integer v) { maxRows = v; }
    public String getDatabase() { return database; }
    public void setDatabase(String v) { database = v; }
}