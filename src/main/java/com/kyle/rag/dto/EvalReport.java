package com.kyle.rag.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
/**
 * 评估报告 DTO:一次评估运行的聚合指标(按 kbId + evalVersion 汇总 kb_eval_result)。
 * hitRate = 命中数/总题数;MRR = Σ(1/命中排名)/总题数(0 = 全未命中);
 * avgFaithfulness 取非空值的平均值(未评估 RAGAS 时为 0)。summary() 输出一行人话摘要。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/11/9 23:49
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class EvalReport {

    /** 评估针对的知识库 ID */
    private Long kbId;
    /** 评估配置标识(对应 kb_eval_result.eval_version) */
    private String evalVersion;
    /** 数据集问题总数 */
    private long totalQuestions;

    /** 评估中途失败的问题数(失败题不产生 EvalResult,也不参与指标计算) */
    private long failedQuestions;
    /** 期望 chunk 被召回的问题数 */
    private long hitCount;
    /** 命中率 = hitCount / totalQuestions([0,1]) */
    private double hitRate;
    /** MRR = 平均(1/命中排名),衡量命中结果排得有多靠前([0,1]) */
    private double mrr;
    /** 忠实度均值(仅统计已评估的非空项) */
    private double avgFaithfulness;
    /** 报告生成时间 */
    private LocalDateTime evalAt;

    /**
     * JPQL 构造器表达式专用(EvalResultRepository.aggregateByVersion):
     * SELECT new EvalReport(...) 的参数个数必须与构造器形参完全一致,
     * 字段增减时若 JPQL 未同步,启动即报 Missing constructor(此处曾因新增字段踩坑)。
     */
    public EvalReport(Long kbId, String evalVersion, long totalQuestions,
                      long hitCount, double hitRate, double mrr,
                      double avgFaithfulness, LocalDateTime evalAt) {
        this.kbId = kbId;
        this.evalVersion = evalVersion;
        this.totalQuestions = totalQuestions;
        this.hitCount = hitCount;
        this.hitRate = hitRate;
        this.mrr = mrr;
        this.avgFaithfulness = avgFaithfulness;
        this.evalAt = evalAt;
    }

    /** 一行人类可读摘要(日志/响应均可用) */
    public String summary() {
        return String.format(
                "评估版本：%s | 问题数：%d | Hit Rate：%.1f%% | MRR：%.4f | Faithfulness：%.4f",
                evalVersion, totalQuestions,
                hitRate * 100, mrr, avgFaithfulness);
    }
}
