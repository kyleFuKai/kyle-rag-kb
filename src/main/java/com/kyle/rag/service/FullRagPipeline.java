package com.kyle.rag.service;

import com.kyle.rag.dto.RagResponse;
import com.kyle.rag.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;
/**
 * 完整 RAG 查询管道:增强检索(HyDE+混合) → Reranker 精排 → 置信度过滤 → Token 预算裁剪
 * → 生成(引用标注版 prompt) → 解析引用关联文档 → 幻觉抽检。
 * 响应携带 sources(仅含回答中实际引用的 chunk,按引用出现顺序)与 notFound 标志;
 * 权限校验由调用层负责;TokenMetrics 由各组件内部上报。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/11/5 23:53
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FullRagPipeline {

    private final EnhancedRetrieverService enhancedRetriever;
    private final RerankerService rerankerService;
    private final ConfidenceFilter confidenceFilter;
    private final ContextTrimmerService contextTrimmer;
    private final SourceBuilder sourceBuilder;
    private final HallucinationChecker hallucinationChecker;
    private final QueryCacheService queryCacheService;
    private final PermissionService permissionService;
    private final ChatClient chatClient;

    @Value("${reranker.top-n:5}")
    private int rerankerTopN;

    /**
     * 管道的"准备阶段":权限过滤 → 缓存查询 → 检索 → 精排 → 过滤 → 裁剪 → 组装 context。
     * 同步与流式共用这段(唯一实现),差异只在"怎么生成、怎么输出、要不要落库"。
     *
     * @param question 用户问题
     * @param kbIds    请求的知识库(会被权限过滤)
     * @return 准备结果:命中缓存时 cached 非空;否则 context/trimmed 可用于生成
     */
    public Prepared prepare(String question, List<Long> kbIds) {
        // 权限过滤在前:无权库不参与检索;过滤后为空即无权访问(403)。
        // 必须在查缓存之前——否则无权限用户可能命中他人缓存,拿到本无权查看的答案。
        List<Long> readableKbIds = permissionService.filterReadableKbIds(kbIds);
        if (readableKbIds.isEmpty()) {
            throw BizException.forbidden("您对所请求的知识库没有访问权限");
        }

        // 查询缓存:相同问题 + 相同知识库集合直接复用上次结果,跳过检索与生成(省 token 与延迟)
        RagResponse cached = queryCacheService.getFromCache(question, readableKbIds);
        if (cached != null) {
            log.info("[FullRagPipeline] 命中查询缓存：question={}",
                    question.substring(0, Math.min(30, question.length())));
            return new Prepared(cached, null, List.of(), readableKbIds);
        }

        // Step 1：增强检索（混合检索 + HyDE）
        List<HybridRetrieverService.ScoredChunk> candidates =
                enhancedRetriever.retrieveWithHyde(question, readableKbIds, 20);
        if (candidates.isEmpty()) {
            return new Prepared(null, null, List.of(), readableKbIds);
        }

        // Step 2：Reranker 精排
        List<HybridRetrieverService.ScoredChunk> reranked =
                rerankerService.rerank(question, candidates, rerankerTopN);

        // Step 3：置信度过滤
        List<HybridRetrieverService.ScoredChunk> filtered = confidenceFilter.filter(reranked);
        if (filtered.isEmpty()) {
            return new Prepared(null, null, List.of(), readableKbIds);
        }

        // Step 4：上下文裁剪（控制 Token 预算）
        List<HybridRetrieverService.ScoredChunk> trimmed = contextTrimmer.trim(filtered);

        // Step 5：构建带引用编号的 context
        return new Prepared(null, buildContext(trimmed), trimmed, readableKbIds);
    }

    /** 准备阶段结果:缓存命中时 cached 非空;检索无果时 context 为 null */
    public record Prepared(RagResponse cached, String context,
                          List<HybridRetrieverService.ScoredChunk> trimmed,
                          List<Long> readableKbIds) {

        /** 是否命中查询缓存 */
        public boolean cacheHit() {
            return cached != null;
        }

        /** 检索无果(无候选或全被置信度过滤掉) */
        public boolean empty() {
            return cached == null && context == null;
        }
    }

    public RagResponse query(String question, List<Long> kbIds) {
        long pipelineStart = System.currentTimeMillis();

        // 准备阶段(权限 → 缓存 → 检索 → 精排 → 过滤 → 裁剪 → context)
        Prepared prepared = prepare(question, kbIds);
        if (prepared.cacheHit()) {
            return prepared.cached();
        }
        if (prepared.empty()) {
            return RagResponse.notFound();
        }

        // Step 5：生成回答
        List<HybridRetrieverService.ScoredChunk> trimmed = prepared.trimmed();
        String context = prepared.context();
        String answer = generateAnswer(question, context, trimmed.size());

        // Step 6：用 SourceBuilder 解析引用标注，关联到文档信息
        List<RagResponse.Source> sources = sourceBuilder.buildSources(answer, trimmed);

        // Step 7：幻觉检测（抽样监控；检测不通过先记日志）
        if (System.currentTimeMillis() % 5 == 0) {
            var faithResult = hallucinationChecker.check(question, answer, context);
            if (!faithResult.isFaithful()) {
                log.warn("[FullRagPipeline] 幻觉检测不通过：score={}，reason={}",
                        faithResult.score(), faithResult.reason());
            }
        }

        long elapsed = System.currentTimeMillis() - pipelineStart;
        log.info("[FullRagPipeline] 完成：question={}，elapsed={}ms，sources={}",
                question.substring(0, Math.min(30, question.length())), elapsed, sources.size());

        RagResponse response = RagResponse.builder()
                .answer(answer)
                .sources(sources)
                .latencyMs((int) elapsed)
                .build();

        // 写回缓存(notFound 结果不写,由 QueryCacheService 内部跳过)
        queryCacheService.putToCache(question, prepared.readableKbIds(), response);
        return response;
    }

    private String buildContext(List<HybridRetrieverService.ScoredChunk> chunks) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            var sc = chunks.get(i);
            sb.append("[参考").append(i + 1).append("]");
            if (sc.chunk().getSectionTitle() != null) {
                sb.append(" ").append(sc.chunk().getSectionTitle());
            }
            sb.append("\n").append(sc.content()).append("\n\n");
        }
        return sb.toString().strip();
    }

    private String generateAnswer(String question, String context, int chunkCount) {
        String systemPrompt = RagPromptTemplate.buildSystemPrompt(context, chunkCount);

        return chatClient.prompt()
                .system(systemPrompt)
                .user(question)
                .call()
                .content();
    }
}
