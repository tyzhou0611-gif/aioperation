package com.aiops.repository;

import com.aiops.entity.KbChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface KbChunkRepository extends JpaRepository<KbChunk, Long> {
    List<KbChunk> findByKbId(Long kbId);
    List<KbChunk> findByDocIdOrderByChunkIndex(Long docId);
    void deleteByDocId(Long docId);
}