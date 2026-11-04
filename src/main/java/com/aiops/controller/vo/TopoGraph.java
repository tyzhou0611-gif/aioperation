package com.aiops.controller.vo;

import java.util.List;

/**
 * 拓扑图整体数据
 */
public class TopoGraph {
    private List<TopoNode> nodes;
    private List<TopoEdge> edges;
    private Stats stats;

    public TopoGraph() {}
    public TopoGraph(List<TopoNode> nodes, List<TopoEdge> edges, Stats stats) {
        this.nodes = nodes; this.edges = edges; this.stats = stats;
    }

    public List<TopoNode> getNodes() { return nodes; } public void setNodes(List<TopoNode> v) { nodes = v; }
    public List<TopoEdge> getEdges() { return edges; } public void setEdges(List<TopoEdge> v) { edges = v; }
    public Stats getStats() { return stats; } public void setStats(Stats v) { stats = v; }

    /** 统计 */
    public static class Stats {
        private long appCount, middlewareCount, serverCount, abnormalCount;
        public long getAppCount() { return appCount; } public void setAppCount(long v) { appCount = v; }
        public long getMiddlewareCount() { return middlewareCount; } public void setMiddlewareCount(long v) { middlewareCount = v; }
        public long getServerCount() { return serverCount; } public void setServerCount(long v) { serverCount = v; }
        public long getAbnormalCount() { return abnormalCount; } public void setAbnormalCount(long v) { abnormalCount = v; }
    }
}