package com.kyle.rag.service;

import com.kyle.rag.entity.DocChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;
/**
 * V3 RAG 问答:检索层升级为 EnhancedRetrieverService(查询改写 HyDE + 混合检索,两级 RRF)。
 * 与 V2 的差异:V2 = HybridRetriever(向量+全文);V3 = 在其结果之上叠加 HyDE 假设答案向量路。
 * 返回 topN 暂硬编码 5(应改读 rag.retrieval.return-top-n,与 V2 对齐)。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/11/5 22:58
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RagQueryServiceV3 {

    private final EnhancedRetrieverService enhancedRetriever;
    private final ChatClient chatClient;

    /** 问答入口:查询改写增强检索 → 空结果短路 → 生成回答 */
    public String query(String question, List<Long> kbIds) {
        List<HybridRetrieverService.ScoredChunk> scoredChunks =
                enhancedRetriever.retrieveWithHyde(question, kbIds, 5);

        if (scoredChunks.isEmpty()) {
            return "在您选择的知识库中未找到与该问题相关的内容。";
        }

        List<DocChunk> chunks = scoredChunks.stream()
                .map(HybridRetrieverService.ScoredChunk::chunk)
                .collect(Collectors.toList());

        return generateAnswer(question, chunks);
    }

    /** 组装防幻觉 prompt 并生成;content 为 null 时返回 null(调用方以此判空) */
    private String generateAnswer(String question, List<DocChunk> chunks) {
        String context = buildContext(chunks);

        String systemPrompt = """
                你是企业内部知识库的智能助手。根据提供的参考内容回答问题。
                规则：只根据参考内容回答，不要编造；如果参考内容不够，告诉用户未找到相关信息。
                
                参考内容：
                ---
                %s
                ---
                """.formatted(context);

        return chatClient.prompt()
                .system(systemPrompt)
                .user(question)
                .call()
                .content();
    }

    /** 组装带编号的参考上下文,编号供回答溯源对应 */
    private String buildContext(List<DocChunk> chunks) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            DocChunk chunk = chunks.get(i);
            sb.append(String.format("[参考%d]", i + 1));
            if (chunk.getSectionTitle() != null) {
                sb.append(" ").append(chunk.getSectionTitle());
            }
            sb.append("\n").append(chunk.getContent()).append("\n\n");
        }
        return sb.toString().strip();
    }
}
