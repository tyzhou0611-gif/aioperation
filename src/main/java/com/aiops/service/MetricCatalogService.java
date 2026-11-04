package com.aiops.service;

import com.aiops.common.ServiceException;
import com.aiops.entity.MetricDefinition;
import com.aiops.repository.MetricDefinitionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 指标注册中心（Metric Catalog）：
 * - 统一维护 Prometheus / HTTP JSON / Push 三类指标定义
 * - 内置 node_exporter 常用指标，首次启动自动写入（不覆盖用户修改）
 * - 供面板、告警、诊断、Copilot 共用
 */
@Service
public class MetricCatalogService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MetricCatalogService.class);

    private static final Set<String> SOURCES = new LinkedHashSet<String>(
            Arrays.asList("prometheus", "http_json", "push"));
    private static final Set<String> UNITS = new LinkedHashSet<String>(
            Arrays.asList("pct", "bps", "bytes", "num", "ms", "count"));
    private static final Set<String> VALUE_TYPES = new LinkedHashSet<String>(
            Arrays.asList("gauge", "counter"));
    private static final Set<String> SCOPE_TYPES = new LinkedHashSet<String>(
            Arrays.asList("GLOBAL", "PROJECT", "ASSET", "MIDDLEWARE", "APP"));
    private static final int MAX_QUERY = 2000;
    private static final int MAX_LABELS = 20;

    private final MetricDefinitionRepository repo;
    private final ObjectMapper mapper = new ObjectMapper();

    public MetricCatalogService(MetricDefinitionRepository repo) {
        this.repo = repo;
    }

    @Override
    public void run(ApplicationArguments args) {
        try { seedBuiltins(); } catch (Exception e) { log.warn("指标目录内置指标初始化失败: {}", e.getMessage()); }
    }

    // ==================== 查询 ====================

    public List<MetricDefinition> listAll() {
        return repo.findAllByOrderByBuiltinDescNameAsc();
    }

    public List<MetricDefinition> listEnabled() {
        return repo.findByEnabledOrderByBuiltinDescNameAsc(1);
    }

    public MetricDefinition getByCode(String code) {
        if (code == null || code.trim().isEmpty()) throw new ServiceException(400, "指标编码不能为空");
        return repo.findByCode(code.trim())
                .orElseThrow(() -> new ServiceException(404, "指标不存在: " + code));
    }

    public MetricDefinition getById(Long id) {
        return repo.findById(id).orElseThrow(() -> new ServiceException(404, "指标不存在: " + id));
    }

    /** 预览未保存的指标定义：按保存时的同一套规则校验 */
    public MetricDefinition validateForPreview(MetricDefinition in) {
        if (in == null) throw new ServiceException(400, "指标内容不能为空");
        if (in.getCode() == null || in.getCode().trim().isEmpty()) in.setCode("preview_metric");
        if (in.getName() == null || in.getName().trim().isEmpty()) in.setName("预览指标");
        return normalize(in, null);
    }

    // ==================== 增删改 ====================

    @Transactional
    public MetricDefinition create(MetricDefinition input, String owner) {
        MetricDefinition d = normalize(input, null);
        if (repo.existsByCode(d.getCode())) {
            throw new ServiceException(400, "指标编码已存在: " + d.getCode());
        }
        d.setOwner(owner);
        d.setBuiltin(0);
        MetricDefinition saved = repo.save(d);
        log.info("指标已创建: {} ({}) source={}", saved.getCode(), saved.getName(), saved.getSourceType());
        return saved;
    }

    @Transactional
    public MetricDefinition update(Long id, MetricDefinition input) {
        MetricDefinition existing = getById(id);
        MetricDefinition d = normalize(input, existing);
        if (!existing.getCode().equals(d.getCode()) && repo.existsByCode(d.getCode())) {
            throw new ServiceException(400, "指标编码已存在: " + d.getCode());
        }
        existing.setCode(d.getCode());
        existing.setName(d.getName());
        existing.setDescription(d.getDescription());
        existing.setSourceType(d.getSourceType());
        existing.setQuery(d.getQuery());
        existing.setConfigJson(d.getConfigJson());
        existing.setLabelsJson(d.getLabelsJson());
        existing.setUnit(d.getUnit());
        existing.setValueType(d.getValueType());
        existing.setScopeType(d.getScopeType());
        existing.setScopeId(d.getScopeId());
        existing.setThresholdsJson(d.getThresholdsJson());
        existing.setEnabled(d.getEnabled());
        if (input.getOwner() != null) existing.setOwner(input.getOwner());
        MetricDefinition saved = repo.save(existing);
        log.info("指标已更新: {} ({})", saved.getCode(), saved.getName());
        return saved;
    }

    @Transactional
    public void delete(Long id) {
        MetricDefinition existing = getById(id);
        if (existing.getBuiltin() != null && existing.getBuiltin() == 1) {
            throw new ServiceException(400, "内置指标不可删除，可将其停用");
        }
        repo.delete(existing);
        log.info("指标已删除: {}", existing.getCode());
    }

    // ==================== 校验与归一化 ====================

    private MetricDefinition normalize(MetricDefinition in, MetricDefinition existing) {
        if (in == null) throw new ServiceException(400, "指标内容不能为空");
        MetricDefinition d = new MetricDefinition();

        String code = trim(in.getCode());
        if (code.isEmpty() || !code.matches("^[A-Za-z][A-Za-z0-9_.-]{1,99}$")) {
            throw new ServiceException(400, "指标编码需以字母开头，只能包含字母/数字/下划线/点/横线，长度 2-100");
        }
        d.setCode(code);

        String name = trim(in.getName());
        if (name.isEmpty()) throw new ServiceException(400, "指标名称不能为空");
        d.setName(cut(name, 100));
        d.setDescription(cut(trim(in.getDescription()), 500));

        String source = trim(in.getSourceType()).toLowerCase();
        if (source.isEmpty()) source = "prometheus";
        if (!SOURCES.contains(source)) throw new ServiceException(400, "不支持的数据源: " + source);
        d.setSourceType(source);

        String query = trim(in.getQuery());
        if (query.length() > MAX_QUERY) throw new ServiceException(400, "查询语句不能超过 " + MAX_QUERY + " 个字符");
        if ("prometheus".equals(source) && query.isEmpty()) {
            throw new ServiceException(400, "Prometheus 指标必须填写 PromQL 查询语句");
        }
        if ("http_json".equals(source)) {
            if (!query.startsWith("http://") && !query.startsWith("https://")) {
                throw new ServiceException(400, "HTTP JSON 指标地址必须以 http:// 或 https:// 开头");
            }
            validateHttpConfig(in.getConfigJson());
        }
        d.setQuery(query);
        d.setConfigJson(normalizeJson(in.getConfigJson()));

        String labels = normalizeLabels(in.getLabelsJson());
        d.setLabelsJson(labels);

        String unit = trim(in.getUnit()).toLowerCase();
        if (unit.isEmpty()) unit = "num";
        if (!UNITS.contains(unit)) throw new ServiceException(400, "不支持的单位: " + unit);
        d.setUnit(unit);

        String valueType = trim(in.getValueType()).toLowerCase();
        if (valueType.isEmpty()) valueType = "gauge";
        if (!VALUE_TYPES.contains(valueType)) throw new ServiceException(400, "不支持的指标类型: " + valueType);
        d.setValueType(valueType);

        String scope = trim(in.getScopeType()).toUpperCase();
        if (scope.isEmpty()) scope = "GLOBAL";
        if (!SCOPE_TYPES.contains(scope)) throw new ServiceException(400, "不支持的指标范围: " + scope);
        d.setScopeType(scope);
        d.setScopeId(in.getScopeId());

        d.setThresholdsJson(normalizeThresholds(in.getThresholdsJson()));
        d.setEnabled(in.getEnabled() == null ? 1 : in.getEnabled());
        d.setBuiltin(existing == null ? 0 : (existing.getBuiltin() == null ? 0 : existing.getBuiltin()));
        d.setOwner(existing == null ? null : existing.getOwner());
        return d;
    }

    private void validateHttpConfig(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            throw new ServiceException(400, "HTTP JSON 指标需要 configJson，至少包含 jsonPath");
        }
        try {
            JsonNode n = mapper.readTree(raw);
            if (!n.isObject()) throw new IllegalArgumentException("configJson 必须是对象");
            String path = n.path("jsonPath").asText("");
            if (path.isEmpty()) throw new IllegalArgumentException("configJson 缺少 jsonPath");
            JsonNode headers = n.path("headers");
            if (!headers.isMissingNode() && !headers.isNull() && !headers.isObject()) {
                throw new IllegalArgumentException("headers 必须是对象");
            }
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException(400, "configJson 格式错误: " + e.getMessage());
        }
    }

    private String normalizeJson(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            return mapper.writeValueAsString(mapper.readTree(raw));
        } catch (Exception e) {
            throw new ServiceException(400, "JSON 格式错误: " + e.getMessage());
        }
    }

    private String normalizeLabels(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "[]";
        try {
            JsonNode n = mapper.readTree(raw);
            List<String> labels = new ArrayList<String>();
            if (n.isArray()) {
                for (JsonNode x : n) {
                    String v = x.asText("").trim();
                    if (!v.isEmpty() && !labels.contains(v)) labels.add(v);
                    if (labels.size() >= MAX_LABELS) break;
                }
            } else if (n.isTextual()) {
                for (String part : n.asText().split(",")) {
                    String v = part.trim();
                    if (!v.isEmpty() && !labels.contains(v)) labels.add(v);
                    if (labels.size() >= MAX_LABELS) break;
                }
            } else {
                throw new IllegalArgumentException("labels 必须是数组");
            }
            return mapper.writeValueAsString(labels);
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException(400, "labelsJson 格式错误: " + e.getMessage());
        }
    }

    private String normalizeThresholds(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            JsonNode n = mapper.readTree(raw);
            if (!n.isObject()) throw new IllegalArgumentException("thresholds 必须是对象");
            Map<String, Double> out = new LinkedHashMap<String, Double>();
            if (n.has("warning") && n.get("warning").isNumber()) out.put("warning", n.get("warning").asDouble());
            if (n.has("critical") && n.get("critical").isNumber()) out.put("critical", n.get("critical").asDouble());
            return out.isEmpty() ? null : mapper.writeValueAsString(out);
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException(400, "thresholdsJson 格式错误: " + e.getMessage());
        }
    }

    private String trim(String s) { return s == null ? "" : s.trim(); }

    private String cut(String s, int max) { return s.length() <= max ? s : s.substring(0, max); }

    // ==================== 内置指标 ====================

    @Transactional
    public void seedBuiltins() {
        List<Builtin> list = new ArrayList<Builtin>();
        list.add(new Builtin("node_cpu_usage", "CPU 使用率",
                "100 - (avg by(instance) (rate(node_cpu_seconds_total{mode=\"idle\"}[15m])) * 100)",
                "pct", "[\"instance\"]", "{\"warning\":75,\"critical\":90}", "节点 CPU 使用率"));
        list.add(new Builtin("node_memory_usage", "内存使用率",
                "(1 - node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes) * 100",
                "pct", "[\"instance\"]", "{\"warning\":75,\"critical\":90}", "节点内存使用率"));
        list.add(new Builtin("node_disk_usage", "根分区磁盘使用率",
                "max by(instance) ((1 - node_filesystem_avail_bytes{mountpoint=\"/\",fstype!~\"tmpfs|overlay\"} / node_filesystem_size_bytes{mountpoint=\"/\",fstype!~\"tmpfs|overlay\"}) * 100)",
                "pct", "[\"instance\"]", "{\"warning\":80,\"critical\":90}", "根分区磁盘使用率"));
        list.add(new Builtin("node_inode_usage", "根分区 inode 使用率",
                "max by(instance) ((1 - node_filesystem_files_free{mountpoint=\"/\"} / node_filesystem_files{mountpoint=\"/\"}) * 100)",
                "pct", "[\"instance\"]", "{\"warning\":80,\"critical\":90}", "根分区 inode 使用率"));
        list.add(new Builtin("node_load1", "系统负载 (1m)",
                "node_load1", "num", "[\"instance\"]", null, "1 分钟平均负载"));
        list.add(new Builtin("node_network_rx_bps", "网络接收速率",
                "sum by(instance) (rate(node_network_receive_bytes_total{device!~\"lo|veth.*|docker.*|br-.*\"}[15m])) * 8",
                "bps", "[\"instance\"]", null, "网卡接收速率（bps）"));
        list.add(new Builtin("node_network_tx_bps", "网络发送速率",
                "sum by(instance) (rate(node_network_transmit_bytes_total{device!~\"lo|veth.*|docker.*|br-.*\"}[15m])) * 8",
                "bps", "[\"instance\"]", null, "网卡发送速率（bps）"));
        list.add(new Builtin("node_tcp_established", "TCP 连接数",
                "node_netstat_Tcp_CurrEstab", "count", "[\"instance\"]", null, "TCP Established 连接数"));
        list.add(new Builtin("node_procs_running", "运行中进程数",
                "node_procs_running", "count", "[\"instance\"]", null, "R 状态进程数"));
        list.add(new Builtin("node_filefd_allocated", "打开文件描述符",
                "node_filefd_allocated", "count", "[\"instance\"]", null, "已分配文件描述符"));
        list.add(new Builtin("node_iowait", "CPU iowait",
                "avg by(instance) (rate(node_cpu_seconds_total{mode=\"iowait\"}[15m])) * 100",
                "pct", "[\"instance\"]", "{\"warning\":10,\"critical\":30}", "CPU iowait 占比"));
        list.add(new Builtin("node_up", "节点在线状态",
                "up{job=~\"node|node_exporter\"}", "num", "[\"instance\",\"job\"]", null, "1=在线 0=离线"));

        int added = 0;
        for (Builtin b : list) {
            if (repo.findByCode(b.code).isPresent()) continue;
            MetricDefinition d = new MetricDefinition();
            d.setCode(b.code);
            d.setName(b.name);
            d.setDescription(b.description);
            d.setSourceType("prometheus");
            d.setQuery(b.query);
            d.setLabelsJson(b.labels);
            d.setUnit(b.unit);
            d.setValueType("gauge");
            d.setScopeType("GLOBAL");
            d.setThresholdsJson(b.thresholds);
            d.setBuiltin(1);
            d.setEnabled(1);
            d.setOwner("system");
            repo.save(d);
            added++;
        }
        if (added > 0) log.info("指标目录已写入 {} 个内置指标", added);
    }

    private static final class Builtin {
        final String code, name, query, unit, labels, thresholds, description;
        Builtin(String code, String name, String query, String unit, String labels, String thresholds, String description) {
            this.code = code; this.name = name; this.query = query; this.unit = unit;
            this.labels = labels; this.thresholds = thresholds; this.description = description;
        }
    }
}