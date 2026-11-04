package com.aiops.entity;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity @Table(name = "kb_knowledge_base")
public class KbKnowledgeBase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 100) private String name;
    @Column(length = 500) private String description;
    @Column(name = "embedding_model", length = 50) private String embeddingModel;
    @Column(name = "chunk_size") private Integer chunkSize;
    @Column(name = "chunk_overlap") private Integer chunkOverlap;
    @Column(name = "doc_count") private Integer docCount;
    @Column(name = "create_by", length = 50) private String createBy;
    @Column(name = "create_time", updatable = false) private LocalDateTime createTime;
    @Column(name = "update_time") private LocalDateTime updateTime;
    public KbKnowledgeBase() {}
    public Long getId() { return id; } public void setId(Long v) { id = v; }
    public String getName() { return name; } public void setName(String v) { name = v; }
    public String getDescription() { return description; } public void setDescription(String v) { description = v; }
    public String getEmbeddingModel() { return embeddingModel; } public void setEmbeddingModel(String v) { embeddingModel = v; }
    public Integer getChunkSize() { return chunkSize; } public void setChunkSize(Integer v) { chunkSize = v; }
    public Integer getChunkOverlap() { return chunkOverlap; } public void setChunkOverlap(Integer v) { chunkOverlap = v; }
    public Integer getDocCount() { return docCount; } public void setDocCount(Integer v) { docCount = v; }
    public String getCreateBy() { return createBy; } public void setCreateBy(String v) { createBy = v; }
    public LocalDateTime getCreateTime() { return createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    @PrePersist protected void onCreate() { createTime = LocalDateTime.now(); updateTime = LocalDateTime.now(); if (docCount == null) docCount = 0; if (chunkSize == null) chunkSize = 500; if (chunkOverlap == null) chunkOverlap = 50; if (embeddingModel == null) embeddingModel = "keyword"; }
    @PreUpdate protected void onUpdate() { updateTime = LocalDateTime.now(); }
}