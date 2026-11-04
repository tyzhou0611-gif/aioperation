package com.aiops.controller;

import com.aiops.common.PageResult;
import com.aiops.common.Result;
import com.aiops.common.ServiceException;
import com.aiops.entity.MiddlewareAsset;
import com.aiops.repository.MiddlewareAssetRepository;
import com.aiops.service.DbWorkbenchService;
import com.aiops.service.MiddlewareMonitorService;
import com.aiops.service.PasswordEncryptor;
import javax.persistence.criteria.Predicate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 中间件/数据库资产管理：CRUD + 分页 + 按类型/环境/状态筛选
 *
 * 数据库类资产额外维护连接账号/密码/默认库（密码 AES 加密，接口不回显），
 * SQL 工作台的连接由这些资产自动同步。
 */
@RestController
@RequestMapping("/api/middleware")
public class MiddlewareController {

    private static final Logger log = LoggerFactory.getLogger(MiddlewareController.class);

    private static final List<String> DB_TYPES = Arrays.asList(
            "MYSQL", "POSTGRESQL", "ORACLE", "MONGODB", "DAMENG", "GREENPLUM", "GBASE", "ELASTICSEARCH");
    private static final List<String> MW_TYPES = Arrays.asList(
            "KAFKA", "ZOOKEEPER", "REDIS", "NACOS", "RABBITMQ", "HADOOP");

    private final MiddlewareAssetRepository repository;
    private final PasswordEncryptor passwordEncryptor;
    private final DbWorkbenchService dbWorkbenchService;
    private final MiddlewareMonitorService monitorService;

    public MiddlewareController(MiddlewareAssetRepository repository,
                                PasswordEncryptor passwordEncryptor,
                                DbWorkbenchService dbWorkbenchService,
                                MiddlewareMonitorService monitorService) {
        this.repository = repository;
        this.passwordEncryptor = passwordEncryptor;
        this.dbWorkbenchService = dbWorkbenchService;
        this.monitorService = monitorService;
    }

    @GetMapping("/page")
    public Result<PageResult<MiddlewareAsset>> page(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String env,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String tag,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        Specification<MiddlewareAsset> spec = (root, q, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (keyword != null && !keyword.trim().isEmpty()) {
                String p = "%" + keyword.trim() + "%";
                ps.add(cb.or(cb.like(root.get("name"), p), cb.like(root.get("ip"), p),
                        cb.like(root.get("clusterName"), p), cb.like(root.get("tags"), p)));
            }
            if (tag != null && !tag.trim().isEmpty()) {
                ps.add(cb.like(root.get("tags"), "%" + tag.trim() + "%"));
            }
            if (type != null && !type.trim().isEmpty()) {
                ps.add(cb.equal(root.get("type"), type));
            } else if (category != null && !category.trim().isEmpty()) {
                // 分类筛选放到数据库层，避免前端只过滤当前页导致分页/总数错误
                ps.add(root.get("type").in("MW".equalsIgnoreCase(category) ? MW_TYPES : DB_TYPES));
            }
            if (env != null && !env.trim().isEmpty()) ps.add(cb.equal(root.get("env"), env));
            if (status != null && !status.trim().isEmpty()) ps.add(cb.equal(root.get("status"), status));
            return ps.isEmpty() ? cb.conjunction() : cb.and(ps.toArray(new Predicate[0]));
        };
        org.springframework.data.domain.Page<MiddlewareAsset> result = repository.findAll(spec, PageRequest.of(page - 1, pageSize, Sort.by(Sort.Direction.ASC, "id")));
        for (MiddlewareAsset asset : result) decorate(asset);
        return Result.success(PageResult.of(result));
    }

    @GetMapping("/{id}")
    public Result<MiddlewareAsset> detail(@PathVariable Long id) {
        return Result.success(decorate(getById(id)));
    }

    @PostMapping
    public Result<MiddlewareAsset> create(@RequestBody MiddlewareAsset asset) {
        asset.setId(null);
        validateTags(asset.getTags());
        asset.setStatus("OFFLINE");
        applyPassword(asset, true);
        MiddlewareAsset saved = repository.save(asset);
        // 新增后立即做一次端口探测，状态直接落到 ONLINE/OFFLINE（不会出现 UNKNOWN）
        monitorService.probeAndSave(saved);
        return Result.success("新增成功", decorate(saved));
    }

    @PutMapping("/{id}")
    public Result<MiddlewareAsset> update(@PathVariable Long id, @RequestBody MiddlewareAsset update) {
        MiddlewareAsset existing = getById(id);
        validateTags(update.getTags());
        boolean endpointChanged = !Objects.equals(existing.getIp(), update.getIp())
                || !Objects.equals(existing.getPort(), update.getPort())
                || !Objects.equals(existing.getType(), update.getType());
        existing.setName(update.getName());
        existing.setType(update.getType());
        existing.setVersion(update.getVersion());
        existing.setIp(update.getIp());
        existing.setPort(update.getPort());
        existing.setClusterName(update.getClusterName());
        existing.setEnv(update.getEnv());
        // 状态由端口探测自动维护，不再接收页面手工设置
        existing.setServerId(update.getServerId());
        existing.setAppIds(update.getAppIds());
        existing.setTags(update.getTags());
        existing.setExporterUrl(update.getExporterUrl());
        existing.setPrometheusJobName(update.getPrometheusJobName());
        existing.setMonitorEnabled(update.getMonitorEnabled());
        existing.setDbUsername(update.getDbUsername());
        existing.setDbName(update.getDbName());
        existing.setNodeList(update.getNodeList());
        existing.setConsoleUrl(update.getConsoleUrl());
        String plain = update.getDbPassword();
        if (plain != null && !plain.trim().isEmpty()) {
            // 不要 trim 密码本身：部分数据库密码允许前后空格，trim 后会导致工作台连不上
            existing.setDbPasswordEncrypted(passwordEncryptor.encrypt(plain));
            log.info("中间件资产[{}]数据库密码已更新，SQL工作台下次同步时生效", id);
        }
        MiddlewareAsset saved = repository.save(existing);
        if (endpointChanged || saved.getStatus() == null || "UNKNOWN".equals(saved.getStatus())) {
            monitorService.probeAndSave(saved);
        }
        return Result.success("更新成功", decorate(saved));
    }

    // ==================== 端口存活探测 ====================

    /** 探测单个资产的主端口/节点端口，更新状态为 ONLINE/OFFLINE */
    @PostMapping("/{id}/probe")
    public Result<Map<String, Object>> probe(@PathVariable Long id) {
        MiddlewareAsset asset = getById(id);
        Map<String, Object> data = monitorService.probeAndSave(asset);
        boolean online = "ONLINE".equals(data.get("status"));
        return Result.success(online ? "端口存活：在线" : "端口不可达：离线", data);
    }

    /**
     * 批量探测端口存活（用于列表自动探测 / 探测本页）。
     * body.ids 为空时探测全部资产；返回 total/online/offline/results。
     */
    @PostMapping("/probe")
    @SuppressWarnings("unchecked")
    public Result<Map<String, Object>> probeBatch(@RequestBody(required = false) Map<String, Object> body) {
        List<Long> ids = new ArrayList<Long>();
        Object raw = body == null ? null : body.get("ids");
        if (raw instanceof List) {
            for (Object o : (List<Object>) raw) {
                try { ids.add(Long.valueOf(o.toString())); } catch (Exception ignore) { }
            }
        }
        List<MiddlewareAsset> assets = ids.isEmpty() ? repository.findAll() : repository.findAllById(ids);
        List<Map<String, Object>> results = monitorService.probeAndSaveAll(assets);
        long online = 0;
        for (Map<String, Object> r : results) {
            if ("ONLINE".equals(r.get("status"))) online++;
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("total", results.size());
        out.put("online", online);
        out.put("offline", results.size() - online);
        out.put("results", results);
        return Result.success("探测完成：在线 " + online + "/" + results.size(), out);
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        dbWorkbenchService.removeByMiddlewareId(id);
        repository.deleteById(id);
        return Result.success("删除成功", null);
    }

    /** 供拓扑/应用依赖选择器使用的精简列表 */
    @GetMapping("/options")
    public Result<List<MiddlewareAsset>> options() {
        List<MiddlewareAsset> all = repository.findAll();
        for (MiddlewareAsset asset : all) decorate(asset);
        return Result.success(all);
    }

    // ==================== 第16批：Prometheus 探针配置 ====================

    private static final java.util.Map<String, String[]> EXPORTER_MAP = new java.util.HashMap<String, String[]>() {{
        put("KAFKA",         new String[]{"kafka_exporter",         "9308", "/metrics"});
        put("MYSQL",         new String[]{"mysqld_exporter",        "9104", "/metrics"});
        put("POSTGRESQL",    new String[]{"postgres_exporter",      "9187", "/metrics"});
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

    /** 新增时接收明文密码并加密；更新时的密码处理在 update() 内完成 */
    private void applyPassword(MiddlewareAsset body, boolean creating) {
        if (creating) {
            String plain = body.getDbPassword();
            body.setDbPassword(null);
            if (plain != null && !plain.trim().isEmpty()) {
                // 密码原样加密，不做 trim，避免带空格密码被改写
                body.setDbPasswordEncrypted(passwordEncryptor.encrypt(plain));
            }
        }
    }

    /** 标签：JSON 数组字符串，不再限制 3 个/10 字；只做容量保护 */
    private void validateTags(String tags) {
        if (tags == null || tags.trim().isEmpty()) return;
        if (tags.length() > 500) {
            throw new ServiceException(400, "标签内容过长（最多 500 字符），请精简后再保存");
        }
    }

    /** 脱敏：不返回密文，仅返回是否已设置密码 */
    private MiddlewareAsset decorate(MiddlewareAsset asset) {
        if (asset == null) return null;
        asset.setDbPassword(null);
        asset.setDbPasswordSet(asset.getDbPasswordEncrypted() != null && !asset.getDbPasswordEncrypted().isEmpty());
        return asset;
    }
}