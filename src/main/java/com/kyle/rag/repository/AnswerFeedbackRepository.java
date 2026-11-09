package com.kyle.rag.repository;

import com.kyle.rag.entity.AnswerFeedback;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 回答反馈仓库。findByMessageIdAndUserId 服务"改主意"场景:
 * 写反馈前先查有没有,有则更新原记录(满足 UNIQUE(message_id,user_id) 约束)。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/11/9 23:50
 */
public interface AnswerFeedbackRepository extends JpaRepository<AnswerFeedback, Long> {

    /** 查某用户对某消息的既有反馈(upsert 场景:有则改值,无则插入) */
    Optional<AnswerFeedback> findByMessageIdAndUserId(Long messageId, Long userId);
}
