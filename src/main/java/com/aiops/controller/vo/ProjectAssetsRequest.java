package com.aiops.controller.vo;

import java.util.List;

/**
 * 项目资产分配请求
 */
public class ProjectAssetsRequest {

    private List<Long> serverIds;
    private List<Long> middlewareIds;
    private List<Long> appIds;

    public List<Long> getServerIds() { return serverIds; }
    public void setServerIds(List<Long> v) { serverIds = v; }
    public List<Long> getMiddlewareIds() { return middlewareIds; }
    public void setMiddlewareIds(List<Long> v) { middlewareIds = v; }
    public List<Long> getAppIds() { return appIds; }
    public void setAppIds(List<Long> v) { appIds = v; }
}