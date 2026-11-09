package com.kyle.rag.repository;

import com.kyle.rag.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
/**
 * 对话消息仓库:会话内消息按时间正序读取,供多轮上下文与历史回放。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/11/7 14:57
 */
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    /** 会话内全部消息,时间正序 */
    List<ChatMessage> findBySessionIdOrderByCreatedAtAsc(String sessionId);
}
