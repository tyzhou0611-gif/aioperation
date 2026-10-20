package com.aiops.repository;

import com.aiops.entity.SysMenu;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SysMenuRepository extends JpaRepository<SysMenu, Long> {

    List<SysMenu> findByParentIdOrderBySortAsc(Long parentId);

    List<SysMenu> findAllByOrderBySortAsc();
}