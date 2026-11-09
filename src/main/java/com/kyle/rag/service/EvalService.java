package com.kyle.rag.service;

import com.kyle.rag.dto.EvalReport;
import com.kyle.rag.dto.RagResponse;
import com.kyle.rag.entity.*;
import com.kyle.rag.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
/**
 * 评估运行器:对数据集逐题跑"HyDE+混合检索→精排",对照 expectedChunkIds 算命中/排名,
 * 有参考答案的题再跑生成+忠实度检测;结果批量落 kb_eval_result,聚合出 EvalReport。
 * 单题失败不中断评估(记日志跳过);syncQuery 用一次性会话 ID,避免历史串题污染评估。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/11/9 23:51
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EvalService {

    private final EvalDatasetRepository datasetRepository;
    private final EvalResultRepository resultRepository;
    private final EnhancedRetrieverService retriever;
    private final RerankerService rerankerService;
    private final HallucinationChecker hallucinationChecker;
    private final StreamingRagService ragService;

    /**
     * 运行完整评估流水线:逐题评估 → 批量落库 → 聚合出 EvalReport(命中率/MRR/忠实度均值)。
     * 注意:数据集无题时抛 RuntimeException;单题失败计入总数但不产生 EvalResult。
     *
     * @param kbId        知识库 ID
     * @param evalVersion 评估版本标识(如 v1_chunk512_hybrid_reranker)
     * @return 评估摘要报告
     */
    public EvalReport runEvaluation(Long kbId, String evalVersion) {
        List<EvalDataset> questions = datasetRepository.findByKbId(kbId);
        if (questions.isEmpty()) {
            throw new RuntimeException("知识库 " + kbId + " 没有评估数据集，请先录入标准问题");
        }

        log.info("[Eval] 开始评估：kbId={}，version={}，问题数={}", kbId, evalVersion, questions.size());

        List<EvalResult> results = new ArrayList<>();
        int failed = 0;
        int hits = 0;
        double mrr = 0.0;
        double totalFaithfulness = 0.0;
        int evalCount = 0;

        for (EvalDataset question : questions) {
            try {
                EvalResult result = evaluateOne(question, kbId, evalVersion);
                results.add(result);

                if (result.getHit()) hits++;
                if (result.getRank() != null && result.getRank() > 0) {
                    mrr += 1.0 / result.getRank();
                }
                if (result.getFaithfulness() != null) {
                    totalFaithfulness += result.getFaithfulness();
                    evalCount++;
                }

            } catch (Exception e) {
                failed++;
                log.error("[Eval] 问题评估失败：questionId={}，error={}", question.getId(), e.getMessage());
            }
        }

        // 批量保存评估结果
        resultRepository.saveAll(results);

        // 指标分母用实际成功评估的题数:失败题(如模型调用异常)不参与指标,避免命中率被失败稀释
        int evaluated = results.size();
        double hitRate = evaluated == 0 ? 0 : (double) hits / evaluated;
        double mrrScore = evaluated == 0 ? 0 : mrr / evaluated;
        double avgFaithfulness = evalCount == 0 ? 0 : totalFaithfulness / evalCount;

        EvalReport report = EvalReport.builder()
                .kbId(kbId)
                .evalVersion(evalVersion)
                .totalQuestions(questions.size())
                .failedQuestions(failed)
                .hitCount(hits)
                .hitRate(hitRate)
                .mrr(mrrScore)
                .avgFaithfulness(avgFaithfulness)
                .evalAt(LocalDateTime.now())
                .build();

        log.info("[Eval] 评估完成：hitRate={}%，MRR={}，faithfulness={}",
                String.format("%.2f", hitRate * 100),
                String.format("%.4f", mrrScore),
                String.format("%.4f", avgFaithfulness));

        return report;
    }

    /** 单题评估:检索(HyDE+混合)→精排 top10→对照期望 chunk 算 hit/rank→生成+忠实度(仅当有参考答案) */
    private EvalResult evaluateOne(EvalDataset question, Long kbId, String evalVersion) {
        // 执行检索
        List<HybridRetrieverService.ScoredChunk> candidates =
                retriever.retrieveWithHyde(question.getQuestion(), List.of(kbId), 20);
        List<HybridRetrieverService.ScoredChunk> reranked =
                rerankerService.rerank(question.getQuestion(), candidates, 10);

        // 计算 Hit Rate 和 MRR
        Long[] expectedChunkIds = question.getExpectedChunkIds();
        boolean hit = false;
        int rank = 0;

        if (expectedChunkIds != null && expectedChunkIds.length > 0) {
            Set<Long> expected = Set.of(expectedChunkIds);
            for (int i = 0; i < reranked.size(); i++) {
                if (expected.contains(reranked.get(i).id())) {
                    hit = true;
                    rank = i + 1;  // 1-based
                    break;
                }
            }
        }

        // 生成回答并评估忠实性（仅对有参考答案的题跑；标准问题集内每题都评）
        String actualAnswer = null;
        Double faithfulness = null;

        if (question.getExpectedAnswer() != null) {
            // 每题用一次性会话 ID：syncQuery 会注入会话历史（见流式那节），
            // 复用同一个 "eval-session" 会把前一题的问答当历史带进来，污染评估结果
            String evalSessionId = "eval-" + UUID.randomUUID();
            RagResponse response = ragService.syncQuery(
                    question.getQuestion(), List.of(kbId), evalSessionId);
            actualAnswer = response.getAnswer();

            // 忠实性检测：用 rerank 后的 Top 结果做 context，尽量贴近生成时实际用到的上下文
            String context = reranked.stream()
                    .limit(5)
                    .map(HybridRetrieverService.ScoredChunk::content)
                    .collect(Collectors.joining("\n\n"));

            HallucinationChecker.FaithfulnessResult faithResult =
                    hallucinationChecker.check(question.getQuestion(), actualAnswer, context);
            faithfulness = faithResult.score();
        }

        EvalResult result = new EvalResult();
        result.setDatasetId(question.getId());
        result.setEvalVersion(evalVersion);
        result.setHit(hit);
        result.setRank(rank > 0 ? rank : null);
        result.setActualAnswer(actualAnswer);
        result.setFaithfulness(faithfulness);
        result.setEvalAt(LocalDateTime.now());

        return result;
    }

    /** 历史对比:按版本聚合各次评估结果(由仓库的 JPQL 聚合查询实现),用于参数调优对比 */
    public List<EvalReport> compareVersions(Long kbId) {
        // 从数据库查历史评估结果，按版本聚合
        return resultRepository.aggregateByVersion(kbId);
    }
}
