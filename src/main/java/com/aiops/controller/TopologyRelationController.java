package com.aiops.controller;

import com.aiops.common.Result;
import com.aiops.controller.vo.RelationVO;
import com.aiops.entity.AssetRelation;
import com.aiops.service.TopologyRelationService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 拓扑关系 CRUD（用户手动配置连线）+ 关系类型过滤 + 确认自动关系。
 */
@RestController
@RequestMapping("/api/topology/relation")
public class TopologyRelationController {

    private final TopologyRelationService service;

    public TopologyRelationController(TopologyRelationService service) {
        this.service = service;
    }

    @GetMapping
    public Result<List<RelationVO>> list() {
        return Result.success(service.listAll());
    }

    /** 按源/目标类型返回可用关系类型（前端下拉过滤） */
    @GetMapping("/allowed")
    public Result<List<String>> allowed(@RequestParam String sourceType, @RequestParam String targetType) {
        return Result.success(service.allowedRelationTypes(sourceType, targetType));
    }

    @PostMapping
    public Result<AssetRelation> create(@RequestBody AssetRelation rel, Authentication auth) {
        return Result.success("已创建", service.create(rel, userId(auth)));
    }

    @PutMapping("/{id}")
    public Result<AssetRelation> update(@PathVariable Long id, @RequestBody AssetRelation rel) {
        return Result.success("已更新", service.update(id, rel));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.success("已删除", null);
    }

    /** 确认自动推导关系 → 转手动 */
    @PostMapping("/confirm")
    public Result<AssetRelation> confirm(@RequestBody Map<String, Object> body, Authentication auth) {
        String st = str(body.get("sourceType")); Long sid = lng(body.get("sourceId"));
        String tt = str(body.get("targetType")); Long tid = lng(body.get("targetId"));
        String rt = str(body.get("relationType"));
        return Result.success("已确认为手动关系", service.confirmAuto(st, sid, tt, tid, rt, userId(auth)));
    }

    private String str(Object o) { return o == null ? null : o.toString(); }
    private Long lng(Object o) { return o == null ? null : Long.valueOf(o.toString()); }
    private Long userId(Authentication a) { return a != null && a.getPrincipal() != null ? null : null; }
}
