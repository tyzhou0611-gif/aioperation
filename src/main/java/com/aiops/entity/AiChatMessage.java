package com.aiops.entity;

import javax.persistence.*;
import java.time.LocalDateTime;

/** AI 消息 */
@Entity @Table(name = "ai_chat_message")
public class AiChatMessage {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "session_id", nullable = false) private Long sessionId;
    @Column(nullable = false, length = 20) private String role;
    @Lob private String content;
    @Lob private String metadata;
    @Column(name = "create_time", updatable = false) private LocalDateTime createTime;

    public AiChatMessage() {}
    public Long getId() { return id; } public void setId(Long v) { id = v; }
    public Long getSessionId() { return sessionId; } public void setSessionId(Long v) { sessionId = v; }
    public String getRole() { return role; } public void setRole(String v) { role = v; }
    public String getContent() { return content; } public void setContent(String v) { content = v; }
    public String getMetadata() { return metadata; } public void setMetadata(String v) { metadata = v; }
    public LocalDateTime getCreateTime() { return createTime; }
    @PrePersist protected void onCreate() { createTime = LocalDateTime.now(); }
}