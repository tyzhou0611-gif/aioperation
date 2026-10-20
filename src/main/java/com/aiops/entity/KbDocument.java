package com.aiops.entity;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity @Table(name = "kb_document")
public class KbDocument {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "kb_id", nullable = false) private Long kbId;
    @Column(length = 200) private String title;
    @Column(name = "file_name", length = 200) private String fileName;
    @Column(name = "file_type", length = 10) private String fileType;
    @Column(name = "file_path", length = 500) private String filePath;
    @Column(name = "file_size") private Long fileSize;
    @Column(length = 500) private String tags;
    @Column(length = 20) private String source;
    @Column(length = 20) private String status;
    @Column(name = "error_msg", length = 500) private String errorMsg;
    @Column(name = "chunk_count") private Integer chunkCount;
    @Column(name = "create_time", updatable = false) private LocalDateTime createTime;
    @Column(name = "update_time") private LocalDateTime updateTime;
    public KbDocument() {}
    public Long getId() { return id; } public void setId(Long v) { id = v; }
    public Long getKbId() { return kbId; } public void setKbId(Long v) { kbId = v; }
    public String getTitle() { return title; } public void setTitle(String v) { title = v; }
    public String getFileName() { return fileName; } public void setFileName(String v) { fileName = v; }
    public String getFileType() { return fileType; } public void setFileType(String v) { fileType = v; }
    public String getFilePath() { return filePath; } public void setFilePath(String v) { filePath = v; }
    public Long getFileSize() { return fileSize; } public void setFileSize(Long v) { fileSize = v; }
    public String getTags() { return tags; } public void setTags(String v) { tags = v; }
    public String getSource() { return source; } public void setSource(String v) { source = v; }
    public String getStatus() { return status; } public void setStatus(String v) { status = v; }
    public String getErrorMsg() { return errorMsg; } public void setErrorMsg(String v) { errorMsg = v; }
    public Integer getChunkCount() { return chunkCount; } public void setChunkCount(Integer v) { chunkCount = v; }
    public LocalDateTime getCreateTime() { return createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    @PrePersist protected void onCreate() { createTime = LocalDateTime.now(); updateTime = LocalDateTime.now(); if (status == null) status = "PENDING"; if (source == null) source = "UPLOAD"; if (chunkCount == null) chunkCount = 0; }
    @PreUpdate protected void onUpdate() { updateTime = LocalDateTime.now(); }
}