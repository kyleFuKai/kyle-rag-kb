package com.kyle.rag.repository;

import com.kyle.rag.entity.IndexTask;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
/**
 * 索引任务数据访问接口，提供任务的增删改查及文档最新任务查询。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/10/27 11:13
 */
public interface IndexTaskRepository extends JpaRepository<IndexTask, Long>{

    /**
     * 查询指定文档创建时间最新的一条任务，不限制任务状态。
     * Top 默认只取一条，CreatedAtDesc 表示按创建时间倒序；无任务时返回 Optional.empty()。
     */
    Optional<IndexTask> findTopByDocIdOrderByCreatedAtDesc(Long docId);
}
