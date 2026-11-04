package com.aiops.controller;

import com.aiops.entity.ServerAgent;
import com.aiops.entity.ServerAsset;
import com.aiops.repository.ServerAgentRepository;
import com.aiops.service.ServerService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Prometheus HTTP Service Discovery：
 * GET /api/monitor/targets?token=xxx
 * 返回已安装且状态 ONLINE 的 exporter 目标，Prometheus 可直接使用 http_sd_configs。
 * 未配置 app.agents.sd-token 时允许匿名访问（内网使用；生产建议配置 token）。
 */
@RestController
@RequestMapping("/api/monitor")
public class AgentTargetController {

    private final ServerAgentRepository agentRepository;
    private final ServerService serverService;

    @Value("${app.agents.sd-token:}")
    private String sdToken;

    public AgentTargetController(ServerAgentRepository agentRepository, ServerService serverService) {
        this.agentRepository = agentRepository;
        this.serverService = serverService;
    }

    @GetMapping("/targets")
    public ResponseEntity<List<Map<String, Object>>> targets(@RequestParam(required = false) String token) {
        if (sdToken != null && !sdToken.trim().isEmpty()
                && !sdToken.trim().equals(token == null ? "" : token.trim())) {
            return ResponseEntity.status(401).build();
        }
        List<Map<String, Object>> groups = new ArrayList<Map<String, Object>>();
        for (ServerAgent agent : agentRepository.findByStatus("ONLINE")) {
            try {
                ServerAsset server = serverService.getById(agent.getServerId());
                if (server == null || server.getIp() == null || server.getIp().trim().isEmpty()
                        || agent.getPort() == null) continue;
                Map<String, Object> labels = new LinkedHashMap<String, Object>();
                labels.put("job", agent.getExporter());
                labels.put("exporter", agent.getExporter());
                labels.put("server", server.getHostname() == null ? server.getIp() : server.getHostname());
                labels.put("env", server.getEnvironment());
                Map<String, Object> group = new LinkedHashMap<String, Object>();
                group.put("targets", java.util.Collections.singletonList(server.getIp().trim() + ":" + agent.getPort()));
                group.put("labels", labels);
                groups.add(group);
            } catch (Exception ignore) { }
        }
        return ResponseEntity.ok(groups);
    }
}