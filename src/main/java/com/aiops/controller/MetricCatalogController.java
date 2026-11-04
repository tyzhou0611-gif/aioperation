package com.aiops.controller;

import com.aiops.common.Result;
import com.aiops.common.ServiceException;
import com.aiops.entity.MetricDefinition;
import com.aiops.entity.MetricSample;
import com.aiops.repository.MetricSampleRepository;
import com.aiops.service.AccessControlService;
import com.aiops.service.MetricCatalogService;
import com.aiops.service.MetricQueryService;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 指标注册中心 API。
 *
 * 读接口：登录即可
 * 定义管理/预览：需要 settings:source
 * 推送：登录即可（后续可加 API Key）
 */
@RestController
@RequestMapping("/api/metrics")
public class MetricCatalogController {

    private static final int MAX_PUSH_BATCH = 1000;

    private final MetricCatalogService catalogService;
    private final MetricQueryService queryService;
    private final MetricSampleRepository sampleRepository;
    private final AccessControlService accessControl;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ObjectMapper looseMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public MetricCatalogController(MetricCatalogService catalogService, MetricQueryService queryService,
                                   MetricSampleRepository sampleRepository, AccessControlService accessControl) {
        this.catalogService = catalogService;
        this.queryService = queryService;
        this.sampleRepository = sampleRepository;
        this.accessControl = accessControl;
    }

    // ==================== 指标目录 ====================

    /** 指标目录（仅启用的指标；不回传 HTTP 请求头等敏感配置） */
    @GetMapping("/catalog")
    public Result<List<MetricDefinition>> catalog(Authentication auth) {
        List<MetricDefinition> list = catalogService.listEnabled();
        for (MetricDefinition d : list) d.setConfigJson(null);
        return Result.success(list);
    }

    /** 全部指标定义（含停用）；非 settings:source 角色不返回敏感请求配置 */
    @GetMapping("/definitions")
    public Result<List<MetricDefinition>> definitions(Authentication auth) {
        List<MetricDefinition> list = catalogService.listAll();
        if (!canManage(auth)) {
            for (MetricDefinition d : list) d.setConfigJson(null);
        }
        return Result.success(list);
    }

    @GetMapping("/definitions/{id}")
    public Result<MetricDefinition> definition(@PathVariable Long id, Authentication auth) {
        MetricDefinition d = catalogService.getById(id);
        if (!canManage(auth)) d.setConfigJson(null);
        return Result.success(d);
    }

    @PostMapping("/definitions")
    public Result<MetricDefinition> create(@RequestBody MetricDefinition body, Authentication auth) {
        String owner = auth == null ? null : auth.getName();
        return Result.success("指标已创建", catalogService.create(body, owner));
    }

    @PutMapping("/definitions/{id}")
    public Result<MetricDefinition> update(@PathVariable Long id, @RequestBody MetricDefinition body) {
        return Result.success("指标已更新", catalogService.update(id, body));
    }

    @DeleteMapping("/definitions/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        catalogService.delete(id);
        return Result.success("指标已删除", null);
    }

    // ==================== 查询 / 预览 ====================

    @PostMapping("/query")
    public Result<Map<String, Object>> query(@RequestBody Map<String, Object> body) {
        String code = str(body.get("code"));
        if (code.isEmpty()) throw new ServiceException(400, "指标编码不能为空");
        String range = str(body.get("range"));
        Long start = num(body.get("start"));
        Long end = num(body.get("end"));
        if (start == null || end == null) { start = null; end = null; }
        validateRange(range, start, end);
        String instance = str(body.get("instance"));
        Map<String, String> vars = vars(body.get("vars"));
        return Result.success(queryService.query(code, range.isEmpty() ? "1h" : range,
                start, end, instance.isEmpty() ? "all" : instance, vars));
    }

    /** 预览未保存的定义：body.definition 为表单对象 */
    @PostMapping("/preview")
    public Result<Map<String, Object>> preview(@RequestBody Map<String, Object> body) {
        Object raw = body.get("definition");
        MetricDefinition def = looseMapper.convertValue(raw != null ? raw : body, MetricDefinition.class);
        String range = str(body.get("range"));
        Long start = num(body.get("start"));
        Long end = num(body.get("end"));
        if (start == null || end == null) { start = null; end = null; }
        validateRange(range, start, end);
        String instance = str(body.get("instance"));
        Map<String, String> vars = vars(body.get("vars"));
        return Result.success(queryService.preview(def, range.isEmpty() ? "1h" : range,
                start, end, instance.isEmpty() ? "all" : instance, vars));
    }

    // ==================== 外部推送 ====================

    /**
     * 推送样本（单条或批量）：
     * {"metric":"order_qps","labels":{"app":"order"},"value":123.4,"timestamp":1793150000}
     * 或 {"samples":[{...},{...}]}
     */
    @PostMapping("/push")
    public Result<Map<String, Object>> push(@RequestBody Map<String, Object> body) {
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        Object samples = body.get("samples");
        if (samples instanceof List) {
            if (((List<?>) samples).size() > MAX_PUSH_BATCH) {
                throw new ServiceException(400, "单次最多推送 " + MAX_PUSH_BATCH + " 条样本");
            }
            for (Object o : (List<?>) samples) {
                if (o instanceof Map) items.add((Map<String, Object>) o);
            }
        } else {
            items.add(body);
        }
        if (items.isEmpty()) throw new ServiceException(400, "没有可写入的样本");

        int accepted = 0;
        List<String> errors = new ArrayList<String>();
        for (Map<String, Object> item : items) {
            try {
                String code = str(item.get("metric"));
                if (code.isEmpty()) code = str(item.get("code"));
                if (code.isEmpty()) throw new IllegalArgumentException("metric 不能为空");
                MetricDefinition def = catalogService.getByCode(code);
                if (!"push".equalsIgnoreCase(def.getSourceType())) {
                    throw new IllegalArgumentException("指标 " + code + " 不是 push 类型");
                }
                Double value = doubleValue(item.get("value"));
                if (value == null) throw new IllegalArgumentException("value 必须是数字");
                long ts = parseTimestamp(item.get("timestamp"));
                String labels = normalizeLabels(item.get("labels"));
                MetricSample s = new MetricSample();
                s.setMetricCode(code);
                s.setLabelsJson(labels);
                s.setValue(value);
                s.setSampleTime(ts);
                sampleRepository.save(s);
                accepted++;
            } catch (Exception e) {
                if (errors.size() < 10) errors.add(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("accepted", accepted);
        out.put("rejected", items.size() - accepted);
        out.put("errors", errors);
        return Result.success("已接收 " + accepted + " 条样本", out);
    }

    private String normalizeLabels(Object labels) {
        if (!(labels instanceof Map)) return "{}";
        try { return mapper.writeValueAsString(labels); }
        catch (Exception e) { return "{}"; }
    }

    private long parseTimestamp(Object v) {
        Long ts = num(v);
        if (ts == null || ts <= 0) return System.currentTimeMillis();
        // 兼容秒级时间戳
        return ts < 100000000000L ? ts * 1000 : ts;
    }

    private Double doubleValue(Object v) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v == null) return null;
        try { return Double.parseDouble(String.valueOf(v).trim()); } catch (Exception e) { return null; }
    }

    private Long num(Object v) {
        if (v == null) return null;
        if (v instanceof Number) return ((Number) v).longValue();
        try { return Long.parseLong(String.valueOf(v).trim()); } catch (Exception e) { return null; }
    }

    private String str(Object v) { return v == null ? "" : String.valueOf(v).trim(); }

    private Map<String, String> vars(Object raw) {
        if (!(raw instanceof Map)) return new LinkedHashMap<String, String>();
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (Map.Entry<?, ?> e : ((Map<?, ?>) raw).entrySet()) {
            if (e.getKey() != null && e.getValue() != null) out.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
        }
        return out;
    }

    private void validateRange(String range, Long start, Long end) {
        if (start == null || end == null) return;
        long s = Math.min(start, end);
        long e = Math.max(start, end);
        long span = e - s;
        if (span < 60) throw new ServiceException(400, "查询时间范围不能小于 1 分钟");
        if (span > 31L * 24 * 3600) throw new ServiceException(400, "查询时间范围不能超过 31 天");
        long now = Instant.now().getEpochSecond();
        if (e > now + 60) throw new ServiceException(400, "结束时间不能晚于当前时间");
    }

    private boolean canManage(Authentication auth) {
        String username = auth == null ? null : auth.getName();
        if (username == null || "anonymousUser".equals(username)) return false;
        return accessControl.isSuperAdmin(username) || accessControl.hasPermission(username, "settings:source");
    }
}