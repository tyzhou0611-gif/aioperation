package com.aiops.controller;

import com.aiops.common.JsonIdUtils;
import com.aiops.common.Result;
import com.aiops.controller.vo.TopoEdge;
import com.aiops.controller.vo.TopoGraph;
import com.aiops.controller.vo.TopoNode;
import com.aiops.entity.AppAsset;
import com.aiops.entity.AssetRelation;
import com.aiops.entity.MiddlewareAsset;
import com.aiops.entity.ServerAsset;
import com.aiops.repository.AppAssetRepository;
import com.aiops.repository.AssetRelationRepository;
import com.aiops.repository.MiddlewareAssetRepository;
import com.aiops.repository.ServerAssetRepository;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 智能拓扑：合并"用户手动配置关系(asset_relation, 实线 origin=manual)"
 * 与"系统自动推导关系(来自 app_asset 字段, 虚线 origin=auto)"，
 * 同 (source,target) 对若已手动配置则跳过 auto，避免重复连线。
 */
@RestController
@RequestMapping("/api/topology")
public class TopologyController {

    private final ServerAssetRepository serverRepository;
    private final MiddlewareAssetRepository middlewareRepository;
    private final AppAssetRepository appRepository;
    private final AssetRelationRepository relationRepository;

    public TopologyController(ServerAssetRepository serverRepository,
                             MiddlewareAssetRepository middlewareRepository,
                             AppAssetRepository appRepository,
                             AssetRelationRepository relationRepository) {
        this.serverRepository = serverRepository;
        this.middlewareRepository = middlewareRepository;
        this.appRepository = appRepository;
        this.relationRepository = relationRepository;
    }

    @GetMapping("/graph")
    public Result<TopoGraph> graph() {
        List<ServerAsset> servers = serverRepository.findAll();
        List<MiddlewareAsset> mws = middlewareRepository.findAll();
        List<AppAsset> apps = appRepository.findAll();

        List<TopoNode> nodes = new ArrayList<TopoNode>();
        List<TopoEdge> edges = new ArrayList<TopoEdge>();

        for (ServerAsset s : servers) {
            nodes.add(new TopoNode("SRV:" + s.getId(), s.getHostname() != null ? s.getHostname() : s.getIp(),
                    "SERVER", 40, "IP: " + s.getIp() + "\n状态: " + s.getStatus(), null, s.getStatus()));
        }
        for (MiddlewareAsset m : mws) {
            String detail = "类型: " + m.getType() + "\n版本: " + (m.getVersion() == null ? "-" : m.getVersion())
                    + "\n地址: " + m.getIp() + ":" + (m.getPort() == null ? "-" : m.getPort())
                    + "\n环境: " + m.getEnv() + "\n状态: " + m.getStatus();
            nodes.add(new TopoNode("MW:" + m.getId(), m.getName(), "MIDDLEWARE", 46, detail, m.getEnv(), m.getStatus()));
        }
        for (AppAsset a : apps) {
            String detail = "编码: " + a.getCode() + "\n类型: " + a.getType() + "\n版本: " + (a.getVersion() == null ? "-" : a.getVersion())
                    + "\n语言: " + (a.getLanguage() == null ? "-" : a.getLanguage()) + "\n部署: " + (a.getDeployType() == null ? "-" : a.getDeployType())
                    + "\n环境: " + a.getEnv() + "\n状态: " + a.getStatus() + "\n负责人: " + (a.getOwner() == null ? "-" : a.getOwner());
            nodes.add(new TopoNode("APP:" + a.getId(), a.getName(), "APP", 44, detail, a.getEnv(), a.getStatus()));
        }

        // 1) 手动关系（实线）
        Set<String> pairSeen = new HashSet<String>();
        for (AssetRelation r : relationRepository.findAllByOrderByIdAsc()) {
            String sNode = nodeKey(r.getSourceType(), r.getSourceId());
            String tNode = nodeKey(r.getTargetType(), r.getTargetId());
            if (sNode == null || tNode == null) continue;
            pairSeen.add(sNode + "|" + tNode);
            TopoEdge e = new TopoEdge(sNode, tNode, labelOf(r), r.getLineStyle() == null ? "solid" : r.getLineStyle(),
                    categoryOf(r.getRelationType()), "manual", r.getId(),
                    r.getSourceType() + ":" + r.getSourceId(), r.getTargetType() + ":" + r.getTargetId(), r.getRelationType());
            edges.add(e);
        }

        // 2) 自动推导关系（虚线），跳过已手动配置的同向对
        for (MiddlewareAsset m : mws) {
            if (m.getServerId() != null) addAuto(edges, pairSeen, "MW:" + m.getId(), "SRV:" + m.getServerId(),
                    "部署", "gray", 2, "MIDDLEWARE:" + m.getId(), "SERVER:" + m.getServerId(), "DEPLOY_ON");
        }
        for (AppAsset a : apps) {
            if (a.getServerId() != null) addAuto(edges, pairSeen, "APP:" + a.getId(), "SRV:" + a.getServerId(),
                    "部署", "gray", 2, "APP:" + a.getId(), "SERVER:" + a.getServerId(), "DEPLOY_ON");
            for (Long mwId : JsonIdUtils.parse(a.getDependsMiddlewareIds())) {
                addAuto(edges, pairSeen, "APP:" + a.getId(), "MW:" + mwId, "依赖", "dashed", 0,
                        "APP:" + a.getId(), "MIDDLEWARE:" + mwId, "DEPENDS");
            }
            for (Long appId : JsonIdUtils.parse(a.getDependsAppIds())) {
                addAuto(edges, pairSeen, "APP:" + a.getId(), "APP:" + appId, "调用", "dashed", 1,
                        "APP:" + a.getId(), "APP:" + appId, "CALLS");
            }
        }

        TopoGraph.Stats stats = new TopoGraph.Stats();
        stats.setServerCount(servers.size());
        stats.setMiddlewareCount(mws.size());
        stats.setAppCount(apps.size());
        stats.setAbnormalCount(
                mws.stream().filter(m -> "OFFLINE".equals(m.getStatus())).count()
              + apps.stream().filter(a -> "STOPPED".equals(a.getStatus()) || "ERROR".equals(a.getStatus())).count()
              + servers.stream().filter(s -> "OFFLINE".equals(s.getStatus())).count());

        return Result.success(new TopoGraph(nodes, edges, stats));
    }

    private void addAuto(List<TopoEdge> edges, Set<String> pairSeen, String s, String t, String label,
                         String lineStyle, int category, String sourceKey, String targetKey, String relType) {
        if (pairSeen.contains(s + "|" + t)) return;
        pairSeen.add(s + "|" + t);
        edges.add(new TopoEdge(s, t, label, lineStyle, category, "auto", null, sourceKey, targetKey, relType));
    }

    private String nodeKey(String type, Long id) {
        if ("SERVER".equals(type)) return "SRV:" + id;
        if ("MIDDLEWARE".equals(type)) return "MW:" + id;
        if ("APP".equals(type)) return "APP:" + id;
        return null;
    }

    private String labelOf(AssetRelation r) {
        if (r.getLabel() != null && !r.getLabel().trim().isEmpty()) return r.getLabel();
        return labelByType(r.getRelationType());
    }

    private String labelByType(String rt) {
        if ("DEPENDS".equals(rt)) return "依赖";
        if ("CALLS".equals(rt)) return "调用";
        if ("DEPLOY_ON".equals(rt)) return "部署";
        if ("CONNECTS_TO".equals(rt)) return "连接";
        return rt;
    }

    private int categoryOf(String rt) {
        if ("DEPENDS".equals(rt)) return 0;
        if ("CALLS".equals(rt) || "CONNECTS_TO".equals(rt)) return 1;
        return 2; // DEPLOY_ON
    }
}
