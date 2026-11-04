package com.aiops.controller.vo;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 项目视图（含资产数量，供项目管理页与大屏项目选择器使用）
 */
public class ProjectVO {

    private Long id;
    private String name;
    private String code;
    private String owner;
    private String description;
    private String status;
    private long serverCount;
    private long middlewareCount;
    private long appCount;
    private List<Long> roleIds;
    private List<String> roleNames;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() { return id; } public void setId(Long v) { id = v; }
    public String getName() { return name; } public void setName(String v) { name = v; }
    public String getCode() { return code; } public void setCode(String v) { code = v; }
    public String getOwner() { return owner; } public void setOwner(String v) { owner = v; }
    public String getDescription() { return description; } public void setDescription(String v) { description = v; }
    public String getStatus() { return status; } public void setStatus(String v) { status = v; }
    public long getServerCount() { return serverCount; } public void setServerCount(long v) { serverCount = v; }
    public long getMiddlewareCount() { return middlewareCount; } public void setMiddlewareCount(long v) { middlewareCount = v; }
    public long getAppCount() { return appCount; } public void setAppCount(long v) { appCount = v; }
    public List<Long> getRoleIds() { return roleIds; } public void setRoleIds(List<Long> v) { roleIds = v; }
    public List<String> getRoleNames() { return roleNames; } public void setRoleNames(List<String> v) { roleNames = v; }
    public LocalDateTime getCreateTime() { return createTime; } public void setCreateTime(LocalDateTime v) { createTime = v; }
    public LocalDateTime getUpdateTime() { return updateTime; } public void setUpdateTime(LocalDateTime v) { updateTime = v; }
}