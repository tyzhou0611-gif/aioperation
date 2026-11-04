package com.aiops.service;

import com.aiops.common.ServiceException;
import com.aiops.controller.vo.RelationVO;
import com.aiops.entity.AppAsset;
import com.aiops.entity.AssetRelation;
import com.aiops.entity.MiddlewareAsset;
import com.aiops.entity.ServerAsset;
import com.aiops.repository.AppAssetRepository;
import com.aiops.repository.AssetRelationRepository;
import com.aiops.repository.MiddlewareAssetRepository;
import com.aiops.repository.ServerAssetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 资产关系管理：手动拓扑连线的增删改查 + 业务校验。
 */
@Service
public class TopologyRelationService {

    private static final List<String> ALL_TYPES = Arrays.asList("SERVER", "MIDDLEWARE", "APP");

    private final AssetRelationRepository repo;
    private final ServerAssetRepository serverRepo;
    private final MiddlewareAssetRepository mwRepo;
    private final AppAssetRepository appRepo;

    public TopologyRelationService(AssetRelationRepository repo, ServerAssetRepository serverRepo,
                                  MiddlewareAssetRepository mwRepo, AppAssetRepository appRepo) {
        this.repo = repo; this.serverRepo = serverRepo; this.mwRepo = mwRepo; this.appRepo = appRepo;
    }

    /** 给定源/目标类型，返回允许的关系类型 */
    public List<String> allowedRelationTypes(String sourceType, String targetType) {
        List<String> r = new ArrayList<String>();
        if ("APP".equals(sourceType) && "MIDDLEWARE".equals(targetType)) r.add("DEPENDS");
        if ("APP".equals(sourceType) && "APP".equals(targetType)) r.add("CALLS");
        if ("MIDDLEWARE".equals(sourceType) && "MIDDLEWARE".equals(targetType)) r.add("CONNECTS_TO");
        if (("APP".equals(sourceType) || "MIDDLEWARE".equals(sourceType)) && "SERVER".equals(targetType)) r.add("DEPLOY_ON");
        return r;
    }

    @Transactional
    public AssetRelation create(AssetRelation rel, Long userId) {
        validate(rel);
        if (repo.existsBySourceTypeAndSourceIdAndTargetTypeAndTargetIdAndRelationType(
                rel.getSourceType(), rel.getSourceId(), rel.getTargetType(), rel.getTargetId(), rel.getRelationType())) {
            throw new ServiceException("该关系已存在，不能重复连线");
        }
        rel.setId(null);
        rel.setCreatedBy(userId);
        if (rel.getLineStyle() == null || rel.getLineStyle().trim().isEmpty()) rel.setLineStyle("solid");
        return repo.save(rel);
    }

    @Transactional
    public AssetRelation update(Long id, AssetRelation update) {
        AssetRelation ex = repo.findById(id).orElseThrow(() -> new ServiceException("关系不存在: " + id));
        ex.setSourceType(update.getSourceType()); ex.setSourceId(update.getSourceId());
        ex.setTargetType(update.getTargetType()); ex.setTargetId(update.getTargetId());
        ex.setRelationType(update.getRelationType()); ex.setLabel(update.getLabel());
        ex.setDescription(update.getDescription()); ex.setLineStyle(update.getLineStyle());
        validate(ex);
        return repo.save(ex);
    }

    @Transactional
    public void delete(Long id) {
        if (!repo.existsById(id)) throw new ServiceException("关系不存在: " + id);
        repo.deleteById(id);
    }

    /** 确认自动推导关系 → 转为手动关系（去重） */
    @Transactional
    public AssetRelation confirmAuto(String sourceType, Long sourceId, String targetType, Long targetId,
                                     String relationType, Long userId) {
        if (repo.existsBySourceTypeAndSourceIdAndTargetTypeAndTargetIdAndRelationType(
                sourceType, sourceId, targetType, targetId, relationType)) {
            throw new ServiceException("该关系已存在");
        }
        AssetRelation rel = new AssetRelation();
        rel.setSourceType(sourceType); rel.setSourceId(sourceId);
        rel.setTargetType(targetType); rel.setTargetId(targetId);
        rel.setRelationType(relationType); rel.setLabel("已确认"); rel.setLineStyle("solid");
        return create(rel, userId);
    }

    public List<RelationVO> listAll() {
        List<RelationVO> out = new ArrayList<RelationVO>();
        for (AssetRelation r : repo.findAllByOrderByIdAsc()) {
            RelationVO v = new RelationVO();
            v.setId(r.getId());
            v.setSourceType(r.getSourceType()); v.setSourceId(r.getSourceId());
            v.setTargetType(r.getTargetType()); v.setTargetId(r.getTargetId());
            v.setRelationType(r.getRelationType()); v.setLabel(r.getLabel());
            v.setDescription(r.getDescription()); v.setLineStyle(r.getLineStyle());
            v.setSourceName(name(r.getSourceType(), r.getSourceId()));
            v.setTargetName(name(r.getTargetType(), r.getTargetId()));
            out.add(v);
        }
        return out;
    }

    /** 手动关系去重键集合 "SRV:id|TGT:id|TYPE" */
    public List<String> manualKeys() {
        List<String> keys = new ArrayList<String>();
        for (AssetRelation r : repo.findAll()) {
            keys.add(r.getSourceType() + ":" + r.getSourceId() + "|" + r.getTargetType() + ":" + r.getTargetId() + "|" + r.getRelationType());
        }
        return keys;
    }

    private void validate(AssetRelation rel) {
        if (rel.getSourceType() == null || rel.getTargetType() == null || rel.getRelationType() == null
                || rel.getSourceId() == null || rel.getTargetId() == null) {
            throw new ServiceException("关系的源/目标/类型不能为空");
        }
        if (rel.getSourceType().equals(rel.getTargetType()) && rel.getSourceId().equals(rel.getTargetId())) {
            throw new ServiceException("不允许自己连自己");
        }
        if (!ALL_TYPES.contains(rel.getSourceType()) || !ALL_TYPES.contains(rel.getTargetType())) {
            throw new ServiceException("非法资产类型: " + rel.getSourceType() + "/" + rel.getTargetType());
        }
        // SERVER 不能作为源（方向：应用/中间件 → 服务器）
        if ("SERVER".equals(rel.getSourceType())) {
            throw new ServiceException("服务器不能作为关系起点（应为 APP/MIDDLEWARE → SERVER 部署）");
        }
        List<String> allowed = allowedRelationTypes(rel.getSourceType(), rel.getTargetType());
        if (!allowed.contains(rel.getRelationType())) {
            throw new ServiceException(rel.getSourceType() + " → " + rel.getTargetType()
                    + " 不允许关系类型 " + rel.getRelationType() + "，可选: " + allowed);
        }
    }

    private String name(String type, Long id) {
        try {
            if ("SERVER".equals(type)) { ServerAsset s = serverRepo.findById(id).orElse(null); return s == null ? "?" : (s.getHostname() != null ? s.getHostname() : s.getIp()); }
            if ("MIDDLEWARE".equals(type)) { MiddlewareAsset m = mwRepo.findById(id).orElse(null); return m == null ? "?" : m.getName(); }
            if ("APP".equals(type)) { AppAsset a = appRepo.findById(id).orElse(null); return a == null ? "?" : a.getName(); }
        } catch (Exception ignore) { }
        return "?";
    }
}