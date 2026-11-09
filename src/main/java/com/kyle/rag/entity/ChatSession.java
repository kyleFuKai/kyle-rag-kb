package com.kyle.rag.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
/**
 * 对话会话实体,对应表 kb_chat_session(id 为应用生成的 UUID,非数据库自增)。
 * 一个用户可有多个会话;会话内消息存 kb_chat_message,本表只存会话级元数据。
 * kb_ids 为 JSON 数组字符串(如 "[1,2]"),记录会话关联的知识库。
 */
@Entity
@Table(name = "kb_chat_session")
@Data
public class ChatSession {

    /** 会话 ID,UUID 字符串,由服务层生成后赋值(表主键为 VARCHAR,无自增) */
    @Id
    private String id;

    /** 所属用户 ID,会话归属与隔离依据 */
    @Column(nullable = false)
    private Long userId;

    /** 会话关联的知识库 ID 列表,JSON 数组字符串(如 "[1,2]") */
    @Column(nullable = false)
    private String kbIds;

    /** 会话标题,通常取首条用户消息截断;为空时前端显示"新对话" */
    private String title;

    /** 会话内消息数,每新增一条消息 +1 */
    @Column(nullable = false)
    private Integer messageCount = 0;

    /** 创建时间,入库时自动填充 */
    @CreationTimestamp
    private LocalDateTime createdAt;

    /** 最近活跃时间,每次发消息刷新;会话列表按它倒序排列 */
    @Column(nullable = false)
    private LocalDateTime lastActiveAt = LocalDateTime.now();

    /** 软删除标记,手动维护(本实体未用 @SoftDelete,查询需自行过滤) */
    @Column(nullable = false)
    private Boolean isDeleted = false;
}
