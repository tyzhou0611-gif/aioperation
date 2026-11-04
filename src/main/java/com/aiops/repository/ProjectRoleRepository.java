package com.aiops.repository;

import com.aiops.entity.ProjectRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface ProjectRoleRepository extends JpaRepository<ProjectRole, Long> {
    List<ProjectRole> findByProjectId(Long projectId);
    List<ProjectRole> findByRoleIdIn(Collection<Long> roleIds);

    /** 立即批量删除，避免“删完再插入相同角色”触发唯一键冲突（同 ProjectAssetRepository 的说明）。 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ProjectRole p where p.projectId = :projectId")
    void deleteByProjectId(@Param("projectId") Long projectId);
}