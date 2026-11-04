package com.aiops.repository;

import com.aiops.entity.ProjectAsset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface ProjectAssetRepository extends JpaRepository<ProjectAsset, Long> {

    List<ProjectAsset> findByProjectId(Long projectId);

    /**
     * 立即执行的批量删除（executeUpdate），不要改回派生删除：
     * 派生删除会先加载实体再排队 remove，Hibernate 在同一事务中会先 flush 新插入再删除旧行，
     * 重新保存已存在的 (project, type, asset) 会撞 uk_project_asset 唯一键（接口 500）。
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ProjectAsset p where p.projectId = :projectId")
    void deleteByProjectId(@Param("projectId") Long projectId);

    List<ProjectAsset> findByAssetTypeAndAssetId(String assetType, Long assetId);

    List<ProjectAsset> findByProjectIdAndAssetType(Long projectId, String assetType);

    List<ProjectAsset> findByProjectIdInAndAssetType(Collection<Long> projectIds, String assetType);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ProjectAsset p where p.assetType = :assetType and p.assetId = :assetId")
    void deleteByAssetTypeAndAssetId(@Param("assetType") String assetType, @Param("assetId") Long assetId);
}