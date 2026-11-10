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
import com.kyle.rag.common.AuthConstants;
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

    private final ContextTrimmerService contextTrimmer;
    private final SourceBuilder sourceBuilder;
    private final ChatSessionService sessionService;
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final TokenMetrics tokenMetrics;
    private final QueryCacheService queryCacheService;
    private final FullRagPipeline fullRagPipeline;

    /**
     * 流式 RAG 查询:按 SSE 协议推送,生成完毕后整轮落库并推 done 事件。
     * 客户端中途断开时推送抛异常中断生成,该轮不落库。
     */
    public void streamQuery(String question, List<Long> kbIds, String sessionId, SseEmitter emitter) {
        long start = System.currentTimeMillis();

        try {
            // 多轮追问（有历史）不走缓存；仅首轮可缓存
            List<Message> history = loadHistoryMessages(sessionId);
            boolean cacheable = history.isEmpty();

            // 准备阶段(权限 → 缓存 → 检索 → 精排 → 过滤 → 裁剪 → context),与同步管道同一实现
            // 注意:多轮追问有历史,答案取决于上下文,不能复用首轮缓存
            FullRagPipeline.Prepared prepared = fullRagPipeline.prepare(question, kbIds);
            if (cacheable && prepared.cacheHit()) {
                RagResponse cached = prepared.cached();
                emitter.send(SseEmitter.event().name("token")
                        .data(objectMapper.writeValueAsString(Map.of("t", cached.getAnswer()))));
                String doneData = objectMapper.writeValueAsString(
                        new DonePayload(cached.getSources(), 0));
                emitter.send(SseEmitter.event().name("done").data(doneData));
                emitter.complete();
                sessionService.saveMessage(sessionId, question, cached.getAnswer(),
                        sourceBuilder.sourcesToJson(cached.getSources()), 0);
                return;
            }

            // 缓存未命中，走完整流式管道
            emitter.send(SseEmitter.event()
                    .name("status")
                    .data("{\"type\":\"RETRIEVING\",\"message\":\"正在检索知识库...\"}"));

            if (prepared.empty()) {
                sendNotFound(emitter);
                return;
            }

            emitter.send(SseEmitter.event()
                    .name("status")
                    .data("{\"type\":\"GENERATING\",\"message\":\"已找到相关内容，正在生成回答...\"}"));

            var trimmed = prepared.trimmed();
            String context = prepared.context();
            String systemPrompt = RagPromptTemplate.buildSystemPrompt(context, trimmed.size());

            StringBuilder fullAnswer = new StringBuilder();

            chatClient.prompt()
                    .system(systemPrompt)
                    .messages(history)
                    .user(question)
                    .stream()
                    .content()
                    .doOnNext(token -> {
                        try {
                            fullAnswer.append(token);
                            // token 包 JSON:答案自带换行会被 SSE 当事件分隔符,JSON 转义后前端解析还原
                            emitter.send(SseEmitter.event().name("token")
                                    .data(objectMapper.writeValueAsString(Map.of("t", token))));
                        } catch (IOException e) {
                            log.warn("[StreamRAG] SSE 推送 Token 失败，客户端可能已断开");
                            throw new RuntimeException("SSE 连接断开");
                        }
                    })
                    .blockLast();

            String answer = fullAnswer.toString();
            tokenMetrics.recordGenerationTokens(contextTrimmer.countTokens(answer));

            List<RagResponse.Source> sources = sourceBuilder.buildSources(answer, trimmed);
            String sourcesJson = sourceBuilder.sourcesToJson(sources);
            int latencyMs = (int) (System.currentTimeMillis() - start);

            sessionService.saveMessage(sessionId, question, answer, sourcesJson, latencyMs);

            // 只有首轮（无历史）才写缓存
            if (cacheable) {
                RagResponse response = RagResponse.builder()
                        .answer(answer).sources(sources).latencyMs(latencyMs).build();
                queryCacheService.putToCache(question, prepared.readableKbIds(), response);
            }

            String doneData = objectMapper.writeValueAsString(new DonePayload(sources, latencyMs));
            emitter.send(SseEmitter.event().name("done").data(doneData));
            emitter.complete();

        } catch (Exception e) {
            log.error("[StreamRAG] 流式查询异常：{}", e.getMessage(), e);
            try {
                // 与流式那节统一：用 objectMapper 生成，避免手拼 JSON 出错
                String errData = objectMapper.writeValueAsString(
                        Map.of("message", "生成过程中出现异常"));
                emitter.send(SseEmitter.event().name("error").data(errData));
                emitter.complete();
            } catch (IOException ignored) {}
        }
    }

    /**
     * 同步版本(供测试/对照),流程与流式一致但一次性返回 RagResponse。
     */
    public RagResponse syncQuery(String question, List<Long> kbIds, String sessionId) {
        // 多轮追问（有历史）时同一句问题在不同上下文答案不同，不能走缓存；仅首轮可缓存
        List<Message> history = loadHistoryMessages(sessionId);
        boolean cacheable = history.isEmpty();

        // 准备阶段(权限 → 缓存 → 检索 → 精排 → 过滤 → 裁剪 → context),与流式/无会话管道同一实现
        FullRagPipeline.Prepared prepared = fullRagPipeline.prepare(question, kbIds);
        if (cacheable && prepared.cacheHit()) {
            RagResponse cached = prepared.cached();
            sessionService.saveMessage(sessionId, question, cached.getAnswer(),
                    sourceBuilder.sourcesToJson(cached.getSources()), 0);
            return cached;
        }
        if (prepared.empty()) return RagResponse.notFound();

        long start = System.currentTimeMillis();

        var trimmed = prepared.trimmed();
        String context = prepared.context();
        String systemPrompt = RagPromptTemplate.buildSystemPrompt(context, trimmed.size());

        String answer = chatClient.prompt()
                .system(systemPrompt)
                .messages(history)
                .user(question)
                .call()
                .content();

        tokenMetrics.recordGenerationTokens(contextTrimmer.countTokens(answer));

        List<RagResponse.Source> sources = sourceBuilder.buildSources(answer, trimmed);
        String sourcesJson = sourceBuilder.sourcesToJson(sources);
        int latencyMs = (int) (System.currentTimeMillis() - start);

        sessionService.saveMessage(sessionId, question, answer, sourcesJson, latencyMs);

        RagResponse response = RagResponse.builder()
                .answer(answer)
                .sources(sources)
                .latencyMs(latencyMs)
                .build();

        // 只有首轮（无历史）才写缓存
        if (cacheable) {
            queryCacheService.putToCache(question, prepared.readableKbIds(), response);
        }
        return response;
    }

    /** 会话历史转 Spring AI 消息列表(USER/ASSISTANT 按角色还原),喂给模型做多轮上下文 */
    private List<Message> loadHistoryMessages(String sessionId) {
        return sessionService.getHistory(sessionId).stream()
                .map(m -> AuthConstants.CHAT_ROLE_USER.equals(m.getRole())
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
        emitter.send(SseEmitter.event().name("token")
                .data(objectMapper.writeValueAsString(Map.of("t", msg))));
        emitter.send(SseEmitter.event().name("done").data("{\"sources\":[]}"));
        emitter.complete();
    }

    record DonePayload(List<RagResponse.Source> sources, int latencyMs) {}
}

