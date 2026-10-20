package com.aiops.repository;

import com.aiops.entity.KbDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface KbDocumentRepository extends JpaRepository<KbDocument, Long> {
    List<KbDocument> findByKbIdOrderByCreateTimeDesc(Long kbId);
}