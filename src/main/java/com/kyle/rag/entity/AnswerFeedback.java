package com.kyle.rag.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
/**
 * 回答反馈实体,对应表 kb_answer_feedback:用户对单条回答的赞/踩及可选文字意见。
 * 表上 UNIQUE(message_id, user_id) 保证一人对一消息最多一条;message 表的 feedback
 * 字段是冗余缓存,明细以本表为准(写入时需同步更新消息表)。
 * 反馈数据是线上效果指标(点赞率)与评估优化(差评样本分析)的来源。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/11/9 23:47
 */
@Entity
@Table(name = "kb_answer_feedback")
@Data
public class AnswerFeedback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    /** 主键,数据库自增 */
    private Long id;

    @Column(nullable = false)
    /** 被反馈的消息 ID(kb_chat_message.id) */
    private Long messageId;

    @Column(nullable = false)
    /** 反馈人 userId */
    private Long userId;

    @Column(nullable = false)
    /** 反馈值:1=有用 -1=无用 */
    private Short feedback;

    /** 可选文字反馈 */
    private String comment;

    @CreationTimestamp
    /** 反馈时间,入库时自动填充 */
    private LocalDateTime createdAt;
}
