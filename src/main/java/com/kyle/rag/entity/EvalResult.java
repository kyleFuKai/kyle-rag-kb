package com.kyle.rag.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;
/**
 * 评估执行结果实体,对应表 kb_eval_result:每次跑评估数据集产生一行。
 * 记录该条问答的检索命中情况(hit/rank 决定命中率与 MRR)与生成质量
 * (faithfulness/answer_relevancy,RAGAS 口径);eval_version 标识评估配置,
 * 用于对比不同参数(块大小/检索策略/重排开关)之间的效果差异。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/11/9 23:43
 */
@Entity
@Table(name = "kb_eval_result")
@Data
public class EvalResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    /** 主键,数据库自增 */
    private Long id;

    @Column(nullable = false)
    /** 关联的评估数据集条目 ID(kb_eval_dataset.id) */
    private Long datasetId;

    @Column(nullable = false, length = 50)
    /** 评估配置标识(如 v1_chunk512_hybrid),对比不同参数的效果用 */
    private String evalVersion;

    @Column(nullable = false)
    /** 期望 chunk 是否出现在召回结果中 */
    private Boolean hit;

    /** 期望 chunk 在召回结果中的排名(1-based),MRR 计算取 1/rank */
    private Integer rank;

    @Column(columnDefinition = "TEXT")
    /** 本次实际生成的回答(供人工比对/语义评估) */
    private String actualAnswer;

    /** 忠实度分数:回答是否忠于检索内容(RAGAS 口径,可为空=未评估) */
    private Double faithfulness;

    /** 答案相关度分数:回答与问题的贴合度(可为空=未评估) */
    private Double answerRelevancy;

    @Column(nullable = false)
    /** 评估执行时间,创建时默认当前时间 */
    private LocalDateTime evalAt = LocalDateTime.now();
}
