package com.kyle.rag.repository;

import com.kyle.rag.entity.ChatSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
/**
 * 对话会话仓库。isDeleted 是普通字段(实体未用 @SoftDelete),
 * 查询方法名里显式带上 AndIsDeletedFalse 过滤,调用方不可省略。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/11/7 14:55
 */
public interface ChatSessionRepository extends JpaRepository<ChatSession, String> {

    /** 某用户的会话列表,按最近活跃倒序(会话列表页用) */
    List<ChatSession> findByUserIdAndIsDeletedFalseOrderByLastActiveAtDesc(Long userId);
}
