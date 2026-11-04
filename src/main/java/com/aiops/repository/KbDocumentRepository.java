package com.aiops.repository;

import com.aiops.entity.KbDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface KbDocumentRepository extends JpaRepository<KbDocument, Long> {
    List<KbDocument> findByKbIdOrderByCreateTimeDesc(Long kbId);
    Optional<KbDocument> findByKbIdAndFileName(Long kbId, String fileName);
    long countByKbId(Long kbId);
}