package com.kyle.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kyle.rag.entity.ChatMessage;
import com.kyle.rag.entity.ChatSession;
import com.kyle.rag.repository.ChatMessageRepository;
import com.kyle.rag.repository.ChatSessionRepository;
import com.kyle.rag.security.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
/**
 * 对话会话服务:会话生命周期(获取/创建/续聊/保存消息/读历史)。
 * 续聊约定:sessionId 非空时必须是当前用户已有的会话,否则按异常处理;
 * 每轮对话存 USER/ASSISTANT 两行消息,会话行同步更新消息数/活跃时间/标题(取首条问题)。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/11/7 16:29
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChatSessionService {

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final ObjectMapper objectMapper;

    /** 最多保留的历史轮数（超出后截断旧消息） */
    private static final int MAX_HISTORY_ROUNDS = 5;

    /**
     * 获取已有会话(刷新活跃时间),或创建新会话(UUID 主键由服务层生成)。
     * kbIds 以 JSON 数组字符串落库。
     */
    @Transactional
    public String getOrCreateSession(String sessionId, List<Long> kbIds) {
        if (sessionId != null && !sessionId.isBlank()) {
            sessionRepository.findById(sessionId).ifPresent(s -> {
                s.setLastActiveAt(LocalDateTime.now());
                sessionRepository.save(s);
            });
            return sessionId;
        }

        // 创建新会话
        ChatSession session = new ChatSession();
        session.setId(UUID.randomUUID().toString());
        session.setUserId(UserContext.getUserId());
        session.setKbIds(kbIds.toString());
        session.setMessageCount(0);
        sessionRepository.save(session);

        log.info("[ChatSession] 新建会话：sessionId={}，userId={}",
                session.getId(), UserContext.getUserId());
        return session.getId();
    }

    /**
     * 保存一轮对话:USER/ASSISTANT 两行消息 + 会话行更新(消息数 +2、活跃时间、首条问题截为标题)。
     */
    @Transactional
    public void saveMessage(String sessionId, String question, String answer,
                            String sourcesJson, int latencyMs) {
        // 保存用户消息
        ChatMessage userMsg = new ChatMessage();
        userMsg.setSessionId(sessionId);
        userMsg.setRole("USER");
        userMsg.setContent(question);
        messageRepository.save(userMsg);

        // 保存助手回答
        ChatMessage assistantMsg = new ChatMessage();
        assistantMsg.setSessionId(sessionId);
        assistantMsg.setRole("ASSISTANT");
        assistantMsg.setContent(answer);
        assistantMsg.setSources(sourcesJson);
        assistantMsg.setLatencyMs(latencyMs);
        messageRepository.save(assistantMsg);

        // 更新会话消息数和活跃时间
        sessionRepository.findById(sessionId).ifPresent(s -> {
            s.setMessageCount(s.getMessageCount() + 2);
            s.setLastActiveAt(LocalDateTime.now());
            if (s.getTitle() == null && question.length() > 0) {
                s.setTitle(question.substring(0, Math.min(50, question.length())));
            }
            sessionRepository.save(s);
        });
    }

    /**
     * 读取会话全部消息(按时间正序),供多轮上下文使用;超出部分由调用方按 MAX_HISTORY_ROUNDS 截断。
     */
    public List<ChatMessage> getHistory(String sessionId) {
        List<ChatMessage> all = messageRepository
                .findBySessionIdOrderByCreatedAtAsc(sessionId);

        // 取最近的 N 轮（N * 2 条消息：用户 + 助手）
        int maxMessages = MAX_HISTORY_ROUNDS * 2;
        if (all.size() > maxMessages) {
            all = all.subList(all.size() - maxMessages, all.size());
        }
        return all;
    }
}
