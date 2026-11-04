package com.aiops.controller;

import com.aiops.common.JsonIdUtils;
import com.aiops.common.PageResult;
import com.aiops.common.Result;
import com.aiops.common.ServiceException;
import com.aiops.entity.AppAsset;
import com.aiops.repository.AppAssetRepository;
import javax.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 应用资产管理：CRUD + 分页 + 按环境/状态/类型筛选。
 * 依赖关系(dependsMiddlewareIds / dependsAppIds) 以 JSON 数组字符串存储，用于拓扑连线。
 */
@RestController
@RequestMapping("/api/app")
public class AppController {

    private final AppAssetRepository repository;

    public AppController(AppAssetRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/page")
    public Result<PageResult<AppAsset>> page(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String env,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        Specification<AppAsset> spec = (root, q, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (keyword != null && !keyword.trim().isEmpty()) {
                String p = "%" + keyword.trim() + "%";
                ps.add(cb.or(cb.like(root.get("name"), p), cb.like(root.get("code"), p), cb.like(root.get("owner"), p)));
            }
            if (type != null && !type.trim().isEmpty()) ps.add(cb.equal(root.get("type"), type));
            if (env != null && !env.trim().isEmpty()) ps.add(cb.equal(root.get("env"), env));
            if (status != null && !status.trim().isEmpty()) ps.add(cb.equal(root.get("status"), status));
            return ps.isEmpty() ? cb.conjunction() : cb.and(ps.toArray(new Predicate[0]));
        };
        Page<AppAsset> result = repository.findAll(spec, PageRequest.of(page - 1, pageSize, Sort.by(Sort.Direction.ASC, "id")));
        return Result.success(PageResult.of(result));
    }

    @GetMapping("/{id}")
    public Result<AppAsset> detail(@PathVariable Long id) {
        return Result.success(getById(id));
    }

    @PostMapping
    public Result<AppAsset> create(@RequestBody AppAsset app) {
        app.setId(null);
        if (app.getCode() != null && repository.existsByCode(app.getCode())) {
            throw new ServiceException("应用编码已存在: " + app.getCode());
        }
        return Result.success("新增成功", repository.save(app));
    }

    @PutMapping("/{id}")
    public Result<AppAsset> update(@PathVariable Long id, @RequestBody AppAsset update) {
        AppAsset existing = getById(id);
        existing.setName(update.getName());
        existing.setCode(update.getCode());
        existing.setType(update.getType());
        existing.setVersion(update.getVersion());
        existing.setLanguage(update.getLanguage());
        existing.setDeployType(update.getDeployType());
        existing.setEnv(update.getEnv());
        existing.setStatus(update.getStatus());
        existing.setServerId(update.getServerId());
        existing.setDependsMiddlewareIds(normalizeJson(update.getDependsMiddlewareIds()));
        existing.setDependsAppIds(normalizeJson(update.getDependsAppIds()));
        existing.setOwner(update.getOwner());
        existing.setTags(update.getTags());
        return Result.success("更新成功", repository.save(existing));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        repository.deleteById(id);
        return Result.success("删除成功", null);
    }

    /** 供应用选择器/拓扑使用的全部应用 */
    @GetMapping("/options")
    public Result<List<AppAsset>> options() {
        return Result.success(repository.findAll());
    }

    private String normalizeJson(String v) {
        // 校验并规范化为 JSON 数组字符串；非法则回退为 []
        List<Long> ids = JsonIdUtils.parse(v);
        return JsonIdUtils.toJson(ids);
    }

    private AppAsset getById(Long id) {
        return repository.findById(id).orElseThrow(() -> new ServiceException("应用不存在: " + id));
    }
}