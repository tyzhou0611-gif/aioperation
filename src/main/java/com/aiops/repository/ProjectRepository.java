package com.aiops.repository;

import com.aiops.entity.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProjectRepository extends JpaRepository<Project, Long> {

    Optional<Project> findByCode(String code);

    boolean existsByCode(String code);

    List<Project> findAllByOrderByIdAsc();

    List<Project> findByNameContainingIgnoreCaseOrCodeContainingIgnoreCaseOrderByIdAsc(String name, String code);
}