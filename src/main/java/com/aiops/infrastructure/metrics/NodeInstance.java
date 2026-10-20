package com.aiops.infrastructure.metrics;

/**
 * Prometheus 发现的一个 node_exporter 实例
 */
public class NodeInstance {
    private final String ip;
    private final String port;
    private final boolean online;

    public NodeInstance(String ip, String port, boolean online) {
        this.ip = ip; this.port = port; this.online = online;
    }

    public String getIp() { return ip; }
    public String getPort() { return port; }
    public boolean isOnline() { return online; }
}