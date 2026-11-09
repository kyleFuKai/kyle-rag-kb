package com.kyle.rag.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * RAG 评估数据集实体,对应表 kb_eval_dataset:标准问答对,衡量检索与生成质量的基准。
 * expectedChunkIds 是"期望命中的分块",检索评估时跑一遍问题、看这些分块是否/以何排名被召回
 * (命中率/MRR);配合 kb_eval_result 表记录每次评估的执行结果。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/11/9 23:36
 */
@Entity
@Table(name = "kb_eval_dataset")
@Data
public class EvalDataset {

    /** 主键,数据库自增 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 针对的知识库 ID(评估是按库进行的) */
    @Column(nullable = false)
    private Long kbId;

    /** 标准测试问题(答案应能从对应知识库中找到) */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String question;

    /** 期望答案(参考标准,供人工比对或语义相似度评估;检索指标不依赖它) */
    @Column(columnDefinition = "TEXT")
    private String expectedAnswer;

    /** 期望被召回的分块 ID 数组:命中数/排名决定命中率与 MRR */
    @Column(columnDefinition = "BIGINT[]")
    private Long[] expectedChunkIds;

    /** 创建人 userId */
    @Column(nullable = false)
    private Long createdBy;

    /** 创建时间,入库时自动填充 */
    @CreationTimestamp
    private LocalDateTime createdAt;
}
