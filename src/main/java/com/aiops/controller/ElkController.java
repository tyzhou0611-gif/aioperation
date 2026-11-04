package com.aiops.controller;

import com.aiops.common.Result;
import com.aiops.entity.ElkConnection;
import com.aiops.service.ElkService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * ELK / Elasticsearch 管理
 *
 * - GET/POST/PUT/DELETE /api/elk        连接管理
 * - POST /api/elk/{id}/test             连通性测试（ES + Logstash）
 * - GET  /api/elk/{id}/indices          索引列表
 * - POST /api/elk/{id}/search           日志检索
 */
@RestController
@RequestMapping("/api/elk")
@RequiredArgsConstructor
public class ElkController {

    private final ElkService elkService;

    @GetMapping
    public Result<List<ElkConnection>> list() {
        return Result.success(elkService.list());
    }

    @PostMapping
    public Result<ElkConnection> create(@RequestBody ElkConnection conn) {
        return Result.success("连接已创建", elkService.create(conn));
    }

    @PutMapping("/{id}")
    public Result<ElkConnection> update(@PathVariable Long id, @RequestBody ElkConnection conn) {
        return Result.success("连接已更新", elkService.update(id, conn));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        elkService.delete(id);
        return Result.success("连接已删除", null);
    }

    @PostMapping("/{id}/test")
    public Result<Map<String, Object>> test(@PathVariable Long id) {
        return Result.success(elkService.test(id));
    }

    @GetMapping("/{id}/indices")
    public Result<List<Map<String, Object>>> indices(@PathVariable Long id) {
        return Result.success(elkService.indices(id));
    }

    @PostMapping("/{id}/search")
    public Result<Map<String, Object>> search(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        String query = body.get("query") == null ? "" : body.get("query").toString();
        String index = body.get("index") == null ? "" : body.get("index").toString();
        int size = body.get("size") == null ? 20 : Integer.parseInt(body.get("size").toString());
        return Result.success(elkService.search(id, query, index, size));
    }

    /** 集群健康与节点分配概览 */
    @GetMapping("/{id}/cluster")
    public Result<Map<String, Object>> cluster(@PathVariable Long id) {
        return Result.success(elkService.cluster(id));
    }

    /** 集群分片列表（可按索引/状态/主副本/节点过滤） */
    @GetMapping("/{id}/shards")
    public Result<Map<String, Object>> shards(@PathVariable Long id,
                                              @RequestParam(required = false) String index,
                                              @RequestParam(required = false) String state,
                                              @RequestParam(required = false) String prirep,
                                              @RequestParam(required = false) String node,
                                              @RequestParam(defaultValue = "2000") int limit) {
        return Result.success(elkService.shards(id, index, state, prirep, node, limit));
    }
}