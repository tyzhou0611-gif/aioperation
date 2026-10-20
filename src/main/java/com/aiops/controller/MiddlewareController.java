package com.aiops.controller;

import com.aiops.common.PageResult;
import com.aiops.common.Result;
import com.aiops.common.ServiceException;
import com.aiops.entity.MiddlewareAsset;
import com.aiops.repository.MiddlewareAssetRepository;
import javax.persistence.criteria.Predicate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 中间件资产管理：CRUD + 分页 + 按类型/环境/状态筛选
 */
@RestController
@RequestMapping("/api/middleware")
public class MiddlewareController {

    private final MiddlewareAssetRepository repository;

    public MiddlewareController(MiddlewareAssetRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/page")
    public Result<PageResult<MiddlewareAsset>> page(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String env,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        Specification<MiddlewareAsset> spec = (root, q, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (keyword != null && !keyword.trim().isEmpty()) {
                String p = "%" + keyword.trim() + "%";
                ps.add(cb.or(cb.like(root.get("name"), p), cb.like(root.get("ip"), p), cb.like(root.get("clusterName"), p)));
            }
            if (type != null && !type.trim().isEmpty()) ps.add(cb.equal(root.get("type"), type));
            if (env != null && !env.trim().isEmpty()) ps.add(cb.equal(root.get("env"), env));
            if (status != null && !status.trim().isEmpty()) ps.add(cb.equal(root.get("status"), status));
            return ps.isEmpty() ? cb.conjunction() : cb.and(ps.toArray(new Predicate[0]));
        };
        org.springframework.data.domain.Page<MiddlewareAsset> result = repository.findAll(spec, PageRequest.of(page - 1, pageSize, Sort.by(Sort.Direction.ASC, "id")));
        return Result.success(PageResult.of(result));
    }

    @GetMapping("/{id}")
    public Result<MiddlewareAsset> detail(@PathVariable Long id) {
        return Result.success(getById(id));
    }

    @PostMapping
    public Result<MiddlewareAsset> create(@RequestBody MiddlewareAsset asset) {
        asset.setId(null);
        return Result.success("新增成功", repository.save(asset));
    }

    @PutMapping("/{id}")
    public Result<MiddlewareAsset> update(@PathVariable Long id, @RequestBody MiddlewareAsset update) {
        MiddlewareAsset existing = getById(id);
        existing.setName(update.getName());
        existing.setType(update.getType());
        existing.setVersion(update.getVersion());
        existing.setIp(update.getIp());
        existing.setPort(update.getPort());
        existing.setClusterName(update.getClusterName());
        existing.setEnv(update.getEnv());
        existing.setStatus(update.getStatus());
        existing.setServerId(update.getServerId());
        existing.setAppIds(update.getAppIds());
        existing.setTags(update.getTags());
        existing.setExporterUrl(update.getExporterUrl());
        existing.setPrometheusJobName(update.getPrometheusJobName());
        existing.setMonitorEnabled(update.getMonitorEnabled());
        return Result.success("更新成功", repository.save(existing));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        repository.deleteById(id);
        return Result.success("删除成功", null);
    }

    /** 供拓扑/应用依赖选择器使用的精简列表 */
    @GetMapping("/options")
    public Result<List<MiddlewareAsset>> options() {
        return Result.success(repository.findAll());
    }

    // ==================== 第16批：Prometheus 探针配置 ====================

    private static final java.util.Map<String, String[]> EXPORTER_MAP = new java.util.HashMap<String, String[]>() {{
        put("KAFKA",         new String[]{"kafka_exporter",         "9308", "/metrics"});
        put("MYSQL",         new String[]{"mysqld_exporter",        "9104", "/metrics"});
        put("NACOS",         new String[]{"nacos-actuator",         "8848", "/nacos/actuator/prometheus"});
        put("REDIS",         new String[]{"redis_exporter",         "9121", "/metrics"});
        put("ELASTICSEARCH", new String[]{"elasticsearch_exporter", "9114", "/metrics"});
        put("ZOOKEEPER",     new String[]{"zookeeper_exporter",     "9141", "/metrics"});
        put("RABBITMQ",      new String[]{"rabbitmq_exporter",      "9419", "/metrics"});
    }};

    /**
     * 返回该中间件的 Prometheus 抓取配置 YAML 片段（可直接复制到 prometheus.yml）
     */
    @GetMapping("/{id}/prometheus-config")
    public Result<String> prometheusConfig(@PathVariable Long id) {
        MiddlewareAsset mw = getById(id);
        String[] info = EXPORTER_MAP.get(mw.getType());
        if (info == null) {
            return Result.success("该中间件类型(" + mw.getType() + ")暂不支持 Prometheus 探针");
        }
        String jobName = mw.getPrometheusJobName() != null && !mw.getPrometheusJobName().trim().isEmpty()
                ? mw.getPrometheusJobName() : info[0];
        String port = mw.getPort() != null && mw.getPort() > 0 ? String.valueOf(mw.getPort()) : info[1];
        String ip = mw.getIp() != null ? mw.getIp() : "localhost";
        String yaml = String.format(
            "  - job_name: '%s'%n" +
            "    metrics_path: '%s'%n" +
            "    scrape_interval: 15s%n" +
            "    static_configs:%n" +
            "      - targets: ['%s:%s']%n" +
            "        labels:%n" +
            "          name: '%s'%n" +
            "          type: '%s'",
            jobName, info[2], ip, port, mw.getName(), mw.getType()
        );
        return Result.success(yaml);
    }

    /**
     * 更新中间件的 Prometheus 探针配置
     */
    @PutMapping("/{id}/prometheus-config")
    public Result<MiddlewareAsset> updatePrometheusConfig(@PathVariable Long id, @RequestBody java.util.Map<String, String> body) {
        MiddlewareAsset mw = getById(id);
        if (body.containsKey("exporterUrl")) mw.setExporterUrl(body.get("exporterUrl"));
        if (body.containsKey("prometheusJobName")) mw.setPrometheusJobName(body.get("prometheusJobName"));
        if (body.containsKey("monitorEnabled")) mw.setMonitorEnabled("true".equals(body.get("monitorEnabled")) ? 1 : 0);
        return Result.success("已更新", repository.save(mw));
    }

    private MiddlewareAsset getById(Long id) {
        return repository.findById(id).orElseThrow(() -> new ServiceException("中间件不存在: " + id));
    }
}