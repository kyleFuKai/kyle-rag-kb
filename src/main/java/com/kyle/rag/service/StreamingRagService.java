package com.kyle.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kyle.rag.dto.RagResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
/**
 * 流式 RAG 服务(SSE):检索优化管道(检索→精排→过滤→裁剪) + SSE 逐 Token 推送 + 多轮会话持久化。
 * SSE 事件协议(前端按 event 名消费):
 *   status  — 阶段通知(RETRIEVING 检索中 / GENERATING 生成中)
 *   token   — 生成的增量文本,前端拼接展示
 *   done    — 完成,携带 {sources, latencyMs}
 *   error   — 失败,携带 {message}
 * 多轮:历史消息注入 prompt(近 5 轮),追问模型能记得上文;生成完成后整轮落库。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/11/7 16:46
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StreamingRagService {

    private final EnhancedRetrieverService enhancedRetriever;
    private final RerankerService rerankerService;
    private final ConfidenceFilter confidenceFilter;
    private final ContextTrimmerService contextTrimmer;
    private final SourceBuilder sourceBuilder;
    private final ChatSessionService sessionService;
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final TokenMetrics tokenMetrics;

    /**
     * 流式 RAG 查询:按 SSE 协议推送,生成完毕后整轮落库并推 done 事件。
     * 客户端中途断开时推送抛异常中断生成,该轮不落库。
     */
    public void streamQuery(String question, List<Long> kbIds, String sessionId, SseEmitter emitter) {
        long start = System.currentTimeMillis();

        try {
            // Step 1：推送"检索中"状态
            emitter.send(SseEmitter.event()
                    .name("status")
                    .data("{\"type\":\"RETRIEVING\",\"message\":\"正在检索知识库...\"}"));

            // Step 2：执行检索管道
            var candidates = enhancedRetriever.retrieveWithHyde(question, kbIds, 20);
            var reranked = rerankerService.rerank(question, candidates, 5);
            var filtered = confidenceFilter.filter(reranked);

            if (filtered.isEmpty()) {
                sendNotFound(emitter);
                return;
            }

            var trimmed = contextTrimmer.trim(filtered);

            // Step 3：推送"生成中"状态
            emitter.send(SseEmitter.event()
                    .name("status")
                    .data("{\"type\":\"GENERATING\",\"message\":\"已找到相关内容，正在生成回答...\"}"));

            // Step 4：构建 Prompt（含多轮历史）
            String context = buildContext(trimmed);
            String systemPrompt = RagPromptTemplate.buildSystemPrompt(context, trimmed.size());
            List<Message> history = loadHistoryMessages(sessionId);   // 取近 N 轮历史做上下文

            // Step 5：流式生成，逐 Token 推送
            StringBuilder fullAnswer = new StringBuilder();

            chatClient.prompt()
                    .system(systemPrompt)
                    .messages(history)          // 把历史注入，模型才"记得"上文，多轮追问才成立
                    .user(question)
                    .stream()
                    .content()
                    .doOnNext(token -> {
                        try {
                            fullAnswer.append(token);
                            emitter.send(SseEmitter.event()
                                    .name("token")
                                    .data(token));
                        } catch (IOException e) {
                            log.warn("[StreamRAG] SSE 推送 Token 失败，客户端可能已断开");
                            throw new RuntimeException("SSE 连接断开");
                        }
                    })
                    .blockLast();  // 等待生成完成

            // Step 6：保存对话记录 + 记录 Generation Token
            String answer = fullAnswer.toString();
            int genTokens = contextTrimmer.countTokens(answer);
            tokenMetrics.recordGenerationTokens(genTokens);

            List<RagResponse.Source> sources = sourceBuilder.buildSources(answer, trimmed);
            String sourcesJson = sourceBuilder.sourcesToJson(sources);
            sessionService.saveMessage(sessionId, question, answer, sourcesJson,
                    (int) (System.currentTimeMillis() - start));

            // Step 7：推送来源信息（done 事件）
            String doneData = objectMapper.writeValueAsString(
                    new DonePayload(sources, (int) (System.currentTimeMillis() - start)));
            emitter.send(SseEmitter.event().name("done").data(doneData));
            emitter.complete();

        } catch (Exception e) {
            log.error("[StreamRAG] 流式生成失败：{}", e.getMessage(), e);
            try {
                // 用 ObjectMapper 拼 JSON，避免异常信息里的引号/换行把 data 打成非法 JSON
                String errData = objectMapper.writeValueAsString(
                        Map.of("message", "生成失败：" + e.getMessage()));
                emitter.send(SseEmitter.event().name("error").data(errData));
                emitter.complete();
            } catch (IOException ignored) {}
        }
    }

    /**
     * 同步版本(供测试/对照),流程与流式一致但一次性返回 RagResponse。
     */
    public RagResponse syncQuery(String question, List<Long> kbIds, String sessionId) {
        long start = System.currentTimeMillis();

        var candidates = enhancedRetriever.retrieveWithHyde(question, kbIds, 20);
        var reranked = rerankerService.rerank(question, candidates, 5);
        var filtered = confidenceFilter.filter(reranked);

        if (filtered.isEmpty()) return RagResponse.notFound();

        var trimmed = contextTrimmer.trim(filtered);
        String context = buildContext(trimmed);
        String systemPrompt = RagPromptTemplate.buildSystemPrompt(context, trimmed.size());
        List<Message> history = loadHistoryMessages(sessionId);

        String answer = chatClient.prompt()
                .system(systemPrompt)
                .messages(history)
                .user(question)
                .call()
                .content();

        int genTokens = contextTrimmer.countTokens(answer);
        tokenMetrics.recordGenerationTokens(genTokens);

        List<RagResponse.Source> sources = sourceBuilder.buildSources(answer, trimmed);
        String sourcesJson = sourceBuilder.sourcesToJson(sources);
        int latencyMs = (int) (System.currentTimeMillis() - start);

        sessionService.saveMessage(sessionId, question, answer, sourcesJson, latencyMs);

        return RagResponse.builder()
                .answer(answer)
                .sources(sources)
                .latencyMs(latencyMs)
                .build();
    }

    /** 会话历史转 Spring AI 消息列表(USER/ASSISTANT 按角色还原),喂给模型做多轮上下文 */
    private List<Message> loadHistoryMessages(String sessionId) {
        return sessionService.getHistory(sessionId).stream()
                .map(m -> "USER".equals(m.getRole())
                        ? (Message) new UserMessage(m.getContent())
                        : (Message) new AssistantMessage(m.getContent()))
                .toList();
    }

    /** 组装带编号的参考上下文,编号与 RagPromptTemplate 的引用标注规则对应 */
    private String buildContext(List<HybridRetrieverService.ScoredChunk> chunks) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            var sc = chunks.get(i);
            sb.append("[参考").append(i + 1).append("]");
            if (sc.chunk().getSectionTitle() != null) sb.append(" ").append(sc.chunk().getSectionTitle());
            sb.append("\n").append(sc.content()).append("\n\n");
        }
        return sb.toString().strip();
    }

    /** 空结果短路:推一段话术 + 空 done 事件,不调模型 */
    private void sendNotFound(SseEmitter emitter) throws IOException {
        String msg = "在知识库中未找到与该问题相关的内容。请尝试用不同关键词提问，或联系相关部门。";
        emitter.send(SseEmitter.event().name("token").data(msg));
        emitter.send(SseEmitter.event().name("done").data("{\"sources\":[]}"));
        emitter.complete();
    }

    record DonePayload(List<RagResponse.Source> sources, int latencyMs) {}
}

