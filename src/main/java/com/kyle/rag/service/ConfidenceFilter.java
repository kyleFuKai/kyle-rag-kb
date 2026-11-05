package com.kyle.rag.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
/**
 * 置信度过滤器:过滤掉低于阈值的 chunk,避免不相关内容影响生成质量。
 * 只能位于 Reranker 之后:阈值 0.3 匹配的是精排分数 [0,1];
 * 若对 RRF 分数(排名制,约 0.016~0.033)套用同一阈值会把全部结果滤光。
 * 兜底策略:全部低于阈值时保留最高分 1 条,保证管道不空手而归。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/11/5 23:41
 */
@Component
@Slf4j
public class ConfidenceFilter {

    /** 精排分数阈值(注意:语义为 [0,1] 相关性分,不适用于 RRF 分数) */
    @Value("${rag.retrieval.min-score:0.3}")
    private double minScore;

    /**
     * 过滤低置信度的 chunk。
     * 如果过滤后为空，保留分数最高的 1 个（不能完全没有内容）。
     */
    public List<HybridRetrieverService.ScoredChunk> filter(
            List<HybridRetrieverService.ScoredChunk> chunks) {

        List<HybridRetrieverService.ScoredChunk> filtered = chunks.stream()
                .filter(c -> c.score() >= minScore)
                .collect(Collectors.toList());

        if (filtered.isEmpty() && !chunks.isEmpty()) {
            // 至少保留分数最高的 1 个（上游已排序，但以防万一用 max 取最高分）
            HybridRetrieverService.ScoredChunk best = chunks.stream()
                    .max(Comparator.comparingDouble(HybridRetrieverService.ScoredChunk::score))
                    .orElse(chunks.get(0));
            log.debug("[ConfidenceFilter] 所有 chunk 低于阈值 {}，保留最高分1条（score={}）",
                    minScore, best.score());
            filtered = List.of(best);
        }

        int filteredCount = chunks.size() - filtered.size();
        if (filteredCount > 0) {
            log.debug("[ConfidenceFilter] 过滤低置信度 chunk：{}条", filteredCount);
        }

        return filtered;
    }
}
