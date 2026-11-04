package com.aiops.repository;

import com.aiops.entity.AssetRelation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AssetRelationRepository extends JpaRepository<AssetRelation, Long> {
    List<AssetRelation> findAllByOrderByIdAsc();
    boolean existsBySourceTypeAndSourceIdAndTargetTypeAndTargetIdAndRelationType(
            String sourceType, Long sourceId, String targetType, Long targetId, String relationType);
}