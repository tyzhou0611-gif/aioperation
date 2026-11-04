package com.aiops.entity;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity @Table(name = "kb_chunk")
public class KbChunk {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "doc_id", nullable = false) private Long docId;
    @Column(name = "kb_id", nullable = false) private Long kbId;
    @Column(name = "chunk_index") private Integer chunkIndex;
    @Lob private String content;
    @Column(name = "heading_path", length = 500) private String headingPath;
    private Integer tokens;
    @Lob private String embedding;
    @Column(name = "embedding_status", length = 20) private String embeddingStatus;
    @Column(name = "create_time", updatable = false) private LocalDateTime createTime;
    public KbChunk() {}
    public Long getId() { return id; } public void setId(Long v) { id = v; }
    public Long getDocId() { return docId; } public void setDocId(Long v) { docId = v; }
    public Long getKbId() { return kbId; } public void setKbId(Long v) { kbId = v; }
    public Integer getChunkIndex() { return chunkIndex; } public void setChunkIndex(Integer v) { chunkIndex = v; }
    public String getContent() { return content; } public void setContent(String v) { content = v; }
    public String getHeadingPath() { return headingPath; } public void setHeadingPath(String v) { headingPath = v; }
    public Integer getTokens() { return tokens; } public void setTokens(Integer v) { tokens = v; }
    public String getEmbedding() { return embedding; } public void setEmbedding(String v) { embedding = v; }
    public String getEmbeddingStatus() { return embeddingStatus; } public void setEmbeddingStatus(String v) { embeddingStatus = v; }
    public LocalDateTime getCreateTime() { return createTime; }
    @PrePersist protected void onCreate() { createTime = LocalDateTime.now(); if (embeddingStatus == null) embeddingStatus = "PENDING"; if (tokens == null) tokens = 0; }
}