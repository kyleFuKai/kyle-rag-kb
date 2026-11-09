package com.kyle.rag.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
/**
 * 对话消息实体,对应表 kb_chat_message:会话内的每一条用户提问/助手回答。
 * user 消息与 assistant 消息各占一行;sources 仅 assistant 消息有值
 * (JSON 字符串,结构同 RagResponse.Source[],由 SourceBuilder.sourcesToJson 写入)。
 * 本表无软删除(schema 无 is_deleted 列)。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/11/7 14:47
 */
@Entity
@Table(name = "kb_chat_message")
@Data
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    /** 主键,数据库自增 */
    private Long id;

    @Column(nullable = false)
    /** 所属会话 ID,关联 kb_chat_session.id */
    private String sessionId;

    @Column(nullable = false)
    /** 角色:USER(用户提问)/ ASSISTANT(模型回答) */
    private String role;

    @Column(nullable = false, columnDefinition = "TEXT")
    /** 消息正文;user 存原问题,assistant 存生成的回答(可能含 (来源:[参考N]) 标注) */
    private String content;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    /** 引用来源 JSON 数组(结构同 RagResponse.Source[]);仅 assistant 消息有值 */
    private String sources;

    /** 生成耗时(毫秒);user 消息为 null */
    private Integer latencyMs;

    /** 用户反馈:1=赞 -1=踩 null=未反馈(明细见 kb_answer_feedback) */
    private Short feedback;

    @CreationTimestamp
    /** 创建时间,入库时自动填充 */
    private LocalDateTime createdAt;
}
