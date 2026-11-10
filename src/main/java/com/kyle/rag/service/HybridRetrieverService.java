package com.kyle.rag.service;

import com.kyle.rag.entity.DocChunk;
import com.kyle.rag.exception.BizException;
import com.kyle.rag.repository.DocChunkRepository;
import com.kyle.rag.security.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;
/**
 * 混合检索服务:向量检索(语义) + 全文检索(精确词)双路召回,RRF 融合排序后取 TopN。
 *
 * RRF(Reciprocal Rank Fusion):score = Σ 1/(K + rank),K=60 为业界惯用值;
 * 用排名而非原始分数融合,规避了余弦距离与 ts_rank 量纲不可比的问题;
 * 同一 chunk 两路都命中时分数累加,天然排在单路命中之前。
 *
 * 已知局限:全文路对中文整句查询命中率低(索引与查询两侧都是 simple 分词,
 * 长句切成单个长 token 几乎不可能精确匹配),向量路始终兜底;
 * 查询侧接 jieba 时索引侧需同步改造,否则两侧分词口径不一致。
 * 权限:retrieveWithPermissionCheck(requestedKbIds) 过滤无权库后检索(部分授权时继续答有权部分);
 * retrieve 为无鉴权入口,仅供已受信的内部调用(如权限过滤后的复检)使用。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/11/4 17:13
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HybridRetrieverService {

    private final EmbeddingService embeddingService;
    private final DocChunkRepository chunkRepository;
    private final TsQueryBuilder tsQueryBuilder;
    private final PermissionService permissionService;

    @Value("${rag.retrieval.vector-top-k:20}")
    private int vectorTopK;

    @Value("${rag.retrieval.fulltext-top-k:20}")
    private int fulltextTopK;

    /** RRF 平滑参数，通常取 60 */
    private static final int RRF_K = 60;

    /**
     * 混合检索：向量检索 + 全文检索，RRF 融合排序。
     *
     * @param question 用户问题（原始，未向量化）
     * @param kbIds    要查询的知识库 ID 列表
     * @param topN     最终返回的 chunk 数量（RRF 排序后取 TopN）
     * @return 按 RRF 分数排序的 chunk 列表（含分数信息）
     */
    public List<ScoredChunk> retrieve(String question, List<Long> kbIds, int topN) {
        // Step 1：向量检索
        float[] queryEmbedding = embeddingService.embed(question);
        String embeddingStr = toVectorString(queryEmbedding);

        List<DocChunk> vectorResults = kbIds.stream()
                .flatMap(kbId -> chunkRepository.findByVectorSimilarity(kbId, embeddingStr, vectorTopK).stream())
                .collect(Collectors.toList());

        // Step 2：全文检索
        String tsQuery = tsQueryBuilder.build(question);
        List<DocChunk> fulltextResults = new ArrayList<>();
        if (tsQuery != null) {
            fulltextResults = kbIds.stream()
                    .flatMap(kbId -> chunkRepository.findByFullTextSearch(kbId, tsQuery, fulltextTopK).stream())
                    .collect(Collectors.toList());
        }

        log.debug("[HybridRetriever] 向量检索召回={}，全文检索召回={}",
                vectorResults.size(), fulltextResults.size());

        // Step 3：RRF 融合
        List<ScoredChunk> merged = rrfMerge(vectorResults, fulltextResults);

        // Step 4：取 TopN
        List<ScoredChunk> topResults = merged.stream()
                .limit(topN)
                .collect(Collectors.toList());

        log.info("[HybridRetriever] RRF 融合后 TopN={}，返回 {} 条", topN, topResults.size());
        return topResults;
    }

    /**
     * 权限安全的混合检索——在调用前过滤 kbIds。
     * 用户传入 kbIds，只有实际有权限的才会被检索。
     */
    public List<ScoredChunk> retrieveWithPermissionCheck(
            String question, List<Long> requestedKbIds, int topN) {

        List<Long> allowedKbIds = permissionService.filterReadableKbIds(requestedKbIds);

        if (allowedKbIds.isEmpty()) {
            throw BizException.forbidden("您对所请求的知识库没有访问权限");
        }

        if (allowedKbIds.size() < requestedKbIds.size()) {
            List<Long> denied = requestedKbIds.stream()
                    .filter(id -> !allowedKbIds.contains(id))
                    .toList();
            log.warn("[权限过滤] userId={} 无权访问 kbIds={}，已过滤",
                    UserContext.getUserId(), denied);
        }

        return retrieve(question, allowedKbIds, topN);
    }


    /**
     * RRF 融合两路结果。
     * 去重：同一个 chunk 出现在两路结果中时，分数累加。
     */
    private List<ScoredChunk> rrfMerge(List<DocChunk> vectorList, List<DocChunk> fulltextList) {
        // key: chunkId → RRF 分数
        Map<Long, Double> scoreMap = new LinkedHashMap<>();
        Map<Long, DocChunk> chunkMap = new HashMap<>();

        // 向量检索结果计分
        for (int rank = 0; rank < vectorList.size(); rank++) {
            DocChunk chunk = vectorList.get(rank);
            double rrfScore = 1.0 / (RRF_K + rank + 1);
            scoreMap.merge(chunk.getId(), rrfScore, Double::sum);
            chunkMap.put(chunk.getId(), chunk);
        }

        // 全文检索结果计分（累加）
        for (int rank = 0; rank < fulltextList.size(); rank++) {
            DocChunk chunk = fulltextList.get(rank);
            double rrfScore = 1.0 / (RRF_K + rank + 1);
            scoreMap.merge(chunk.getId(), rrfScore, Double::sum);
            chunkMap.put(chunk.getId(), chunk);
        }

        // 按 RRF 分数降序排列
        return scoreMap.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .map(e -> new ScoredChunk(chunkMap.get(e.getKey()), e.getValue()))
                .collect(Collectors.toList());
    }

    /** float[] → pgvector 文本格式 [0.1,0.2,...] */
    private String toVectorString(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(embedding[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    /** 带分数的 Chunk 包装类 */
    public record ScoredChunk(DocChunk chunk, double score) {
        public Long id() { return chunk.getId(); }
        public String content() { return chunk.getContent(); }
    }
}
