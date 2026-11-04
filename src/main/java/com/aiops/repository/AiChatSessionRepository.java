package com.aiops.repository;

import com.aiops.entity.AiChatSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AiChatSessionRepository extends JpaRepository<AiChatSession, Long> {
    List<AiChatSession> findByUsernameOrderByIdDesc(String username);
    List<AiChatSession> findByUserIdOrderByIdDesc(Long userId);
}