package com.kyle.rag.service;

import com.kyle.rag.entity.*;
import com.kyle.rag.repository.*;
import com.kyle.rag.security.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
/**
 * 反馈服务:用户对单条回答点赞/点踩。
 * upsert 语义:同用户同消息只保留一条(满足 UNIQUE 约束),重复提交覆盖旧值(允许改主意);
 * 双写一致性:AnswerFeedback(明细)与 ChatMessage.feedback(冗余缓存)在同一事务内更新。
 * 差评即线上质量信号——候选进入评估数据集的人工审核队列。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/11/10 00:41
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FeedbackService {

    private final AnswerFeedbackRepository feedbackRepository;
    private final ChatMessageRepository messageRepository;
    private final EvalDatasetRepository datasetRepository;

    /**
     * 提交反馈:upsert 明细表 + 更新消息冗余字段(同事务);feedback 取 1/-1。
     * 差评只记日志做候选标记,正式入评估集需人工审核(暂无管理后台,先走日志审计)。
     */
    @Transactional
    public void submitFeedback(Long messageId, int feedback, String comment) {
        ChatMessage message = messageRepository.findById(messageId)
                .orElseThrow(() -> new RuntimeException("消息不存在"));

        // 同一用户对同一消息只保留一条反馈，重复提交则覆盖
        Long userId = UserContext.getUserId();
        AnswerFeedback fb = feedbackRepository.findByMessageIdAndUserId(messageId, userId)
                .orElseGet(() -> {
                    AnswerFeedback newFb = new AnswerFeedback();
                    newFb.setMessageId(messageId);
                    newFb.setUserId(userId);
                    return newFb;
                });
        fb.setFeedback((short) feedback);
        fb.setComment(comment);
        feedbackRepository.save(fb);

        // 更新消息的 feedback 字段
        message.setFeedback((short) feedback);
        messageRepository.save(message);

        // 差评：把这个问题加入评估候选（人工审核后加入正式评估集）
        if (feedback == -1) {
            log.info("[Feedback] 差评记录，候选加入评估集：messageId={}", messageId);
            // 这里只记录日志，实际需要一个管理后台人工审核后才加入 eval_dataset
        }

        log.info("[Feedback] 反馈已记录：messageId={}，feedback={}，userId={}",
                messageId, feedback, UserContext.getUserId());
    }
}
