package com.kyle.rag.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
/**
 * 索引任务实体，对应表 kb_index_task。
 * 文档上传/重建时插入一条任务，由异步线程（index- 池）拾取执行并更新状态；
 * 失败后在 retry_count < max_retry 范围内重试。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/10/19 23:21
 */
@Entity
@Table(name = "kb_index_task")
@Getter
@Setter
// 不用 @Data：equals/hashCode 随字段变化会让实体进集合后"失踪"，理由同 KnowledgeBase
@ToString(onlyExplicitlyIncluded = true)
public class IndexTask {

    /** 主键，BIGSERIAL 自增 */
    @ToString.Include
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 文档ID（裸 id 关联，无外键） */
    @ToString.Include
    @Column(name = "doc_id", nullable = false)
    private Long docId;

    /** 任务类型：INDEX 首次索引 / REINDEX 重建索引 */
    @Column(name = "task_type", nullable = false, length = 20)
    private String taskType = "INDEX";

    /** 任务状态：PENDING 入队 / RUNNING 执行中 / DONE 成功 / FAILED 失败（失败原因见 errorMsg） */
    @ToString.Include
    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private TaskStatus status = TaskStatus.PENDING;

    /** 已重试次数，每失败一次 +1 */
    @Column(name = "retry_count", nullable = false)
    private Integer retryCount = 0;

    /** 最大重试次数，达到后任务终止为 FAILED 不再重试 */
    @Column(name = "max_retry", nullable = false)
    private Integer maxRetry = 3;

    /** 最近一次失败原因 */
    @Column(name = "error_msg", columnDefinition = "TEXT")
    private String errorMsg;

    /** 入队时间，入库时 Hibernate 自动填充 */
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 开始执行时间，任务被拾取时写入 */
    @Column(name = "started_at")
    private LocalDateTime startedAt;

    /** 结束时间，DONE / FAILED 时写入 */
    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    /** 文档索引任务状态枚举，值存库为字符串 */
    public enum TaskStatus {
        PENDING, RUNNING, DONE, FAILED
    }

    /** 是否还能重试：已失败且未达重试上限 */
    public boolean canRetry() {
        return retryCount < maxRetry && status == TaskStatus.FAILED;
    }
}
