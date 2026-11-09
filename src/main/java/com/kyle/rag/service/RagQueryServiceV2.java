package com.kyle.rag.service;

import com.kyle.rag.entity.DocChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;
/**
 * 升级版 RAG 问答:检索换成混合双路(向量+全文,RRF 融合),取 rag.retrieval.return-top-n 条进 prompt。
 * 与 V1 的差异:检索质量(双路共识);prompt 文案措辞与 V1 略有不同,两版并存期间口径以本类为准。
 * 后续 Reranker 节在此基础上继续升级(消费 ScoredChunk 的 RRF 分数做精排)。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/11/5 00:50
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RagQueryServiceV2 {

    private final HybridRetrieverService hybridRetriever;
    private final ChatClient chatClient;

    @Value("${rag.retrieval.return-top-n:5}")
    private int returnTopN;

    /** 问答入口:混合检索 → 空结果短路返回引导话术 → 生成回答;异常契约与 V1 一致 */
    public String query(String question, List<Long> kbIds) {
        // Step 1：混合检索
        List<HybridRetrieverService.ScoredChunk> scoredChunks =
                hybridRetriever.retrieveWithPermissionCheck(question, kbIds, returnTopN);

        if (scoredChunks.isEmpty()) {
            return buildNotFoundResponse();
        }

        // Step 2：生成回答
        List<DocChunk> chunks = scoredChunks.stream()
                .map(HybridRetrieverService.ScoredChunk::chunk)
                .collect(Collectors.toList());

        return generateAnswer(question, chunks);
    }

    private String generateAnswer(String question, List<DocChunk> chunks) {
        String context = buildContext(chunks);
        String systemPrompt = buildSystemPrompt(context);

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
            DocChunk c = chunks.get(i);
            sb.append("[参考").append(i + 1).append("]");
            if (c.getSectionTitle() != null) sb.append(" ").append(c.getSectionTitle());
            sb.append("\n").append(c.getContent()).append("\n\n");
        }
        return sb.toString().strip();
    }

    /** 防幻觉 system prompt:规则与 V1 同源(措辞略简),改口径两版同步 */
    private String buildSystemPrompt(String context) {
        return """
                你是企业内部知识库的智能助手。根据以下参考内容回答用户问题。
                
                规则：
                1. 只基于参考内容回答，不使用自身知识推测
                2. 参考内容不足时，明确告知"未在知识库找到相关信息"
                3. 回答用中文，准确简洁
                4. 禁止编造参考内容之外的信息
                
                参考内容：
                ---
                %s
                ---
                """.formatted(context);
    }

    /** 检索空结果的短路话术,不调模型 */
    private String buildNotFoundResponse() {
        return "在您选择的知识库中未找到与该问题相关的内容。建议您：\n" +
                "1. 确认问题是否与知识库主题相关\n" +
                "2. 尝试用不同关键词提问\n" +
                "3. 联系相关部门获取准确信息";
    }

}
