package com.aiops.entity;

import javax.persistence.*;
import java.time.LocalDateTime;

/** AI 会话 */
@Entity @Table(name = "ai_chat_session")
public class AiChatSession {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id") private Long userId;
    @Column(length = 50) private String username;
    @Column(length = 200) private String title;
    @Column(name = "create_time", updatable = false) private LocalDateTime createTime;
    @Column(name = "update_time") private LocalDateTime updateTime;

    public AiChatSession() {}
    public Long getId() { return id; } public void setId(Long v) { id = v; }
    public Long getUserId() { return userId; } public void setUserId(Long v) { userId = v; }
    public String getUsername() { return username; } public void setUsername(String v) { username = v; }
    public String getTitle() { return title; } public void setTitle(String v) { title = v; }
    public LocalDateTime getCreateTime() { return createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    @PrePersist protected void onCreate() {
        createTime = LocalDateTime.now(); updateTime = LocalDateTime.now();
    }
    @PreUpdate protected void onUpdate() { updateTime = LocalDateTime.now(); }
}