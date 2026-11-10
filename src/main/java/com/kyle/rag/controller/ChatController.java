package com.kyle.rag.controller;

import com.kyle.rag.dto.ApiResponse;
import com.kyle.rag.dto.ChatRequest;
import jakarta.validation.Valid;
import com.kyle.rag.dto.RagResponse;
import com.kyle.rag.entity.ChatMessage;
import com.kyle.rag.entity.ChatSession;
import com.kyle.rag.repository.ChatMessageRepository;
import com.kyle.rag.repository.ChatSessionRepository;
import com.kyle.rag.exception.BizException;
import com.kyle.rag.security.UserContext;
import com.kyle.rag.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
/**
 * 多轮对话的 REST 入口(/api/v1/chat):SSE 流式问答 + 同步问答 + 会话管理。
 * 权限契约:每次问答对 kbIds 逐个 requireRead;SSE 用 GET 参数(EventSource 不支持 body)。
 * 线程契约:检索/生成在 sseExecutor 线程池执行,用户上下文由 HTTP 线程捕获快照、
 * SSE 线程 set/finally clear(与 IndexTaskLauncher 同款手法)。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/11/8 13:33
 */
@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
@Slf4j
public class ChatController {

    private final StreamingRagService streamingRagService;
    private final ChatSessionService sessionService;
    private final PermissionService permissionService;
    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;

    /** SSE 推送专用线程池(cachedThreadPool,daemon 线程):检索+生成耗时长,不占用 Tomcat 请求线程 */
    private final ExecutorService sseExecutor = Executors.newCachedThreadPool(
            r -> {
                Thread t = new Thread(r);
                t.setName("sse-rag-" + t.getId());
                t.setDaemon(true);
                return t;
            }
    );

    /**
     * 流式问答接口(SSE):返回 SseEmitter,事件协议(status/token/done/error)见 StreamingRagService 类注释。
     * 权限校验与建会话在 HTTP 线程完成,检索与生成转交 SSE 线程。
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(
            @RequestParam String question,
            @RequestParam List<Long> kbIds,
            @RequestParam(required = false) String sessionId) {

        // 校验权限
        kbIds.forEach(permissionService::requireRead);

        // 创建或获取会话
        String sid = sessionService.getOrCreateSession(sessionId, kbIds);

        SseEmitter emitter = new SseEmitter(60_000L);  // 60秒超时

        // 捕获当前 HTTP 线程的用户上下文，传递给 SSE 线程
        Long currentUserId = UserContext.getUserId();
        String currentDeptId = UserContext.getDepartmentId();
        String currentRole = UserContext.getRole();

        sseExecutor.submit(() -> {
            UserContext.set(currentUserId, currentDeptId, currentRole);
            try {
                streamingRagService.streamQuery(question, kbIds, sid, emitter);
            } catch (Exception e) {
                log.error("[Chat] SSE 执行异常：{}", e.getMessage(), e);
                try {
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data("{\"message\":\"系统内部错误，请稍后重试\"}"));
                    emitter.complete();
                } catch (IOException ignored) {}
            } finally {
                UserContext.clear();
            }
        });

        return emitter;
    }

    /** 同步问答:一次性返回 RagResponse(含 sources),权限与会话逻辑与流式一致 */
    @PostMapping
    public ApiResponse<RagResponse> syncChat(@Valid @RequestBody ChatRequest request) {
        request.getKbIds().forEach(permissionService::requireRead);
        String sid = sessionService.getOrCreateSession(request.getSessionId(), request.getKbIds());
        return ApiResponse.ok(streamingRagService.syncQuery(request.getQuestion(), request.getKbIds(), sid));
    }

    /** 当前用户的会话列表,按最近活跃倒序 */
    @GetMapping("/sessions")
    public ApiResponse<List<ChatSession>> listSessions() {
        List<ChatSession> sessions = sessionRepository
                .findByUserIdAndIsDeletedFalseOrderByLastActiveAtDesc(UserContext.getUserId());
        return ApiResponse.ok(sessions);
    }

    /** 指定会话的全部消息(按时间正序),历史回放用;会话必须存在且属于当前用户 */
    @GetMapping("/sessions/{sessionId}/messages")
    public ApiResponse<List<ChatMessage>> getMessages(@PathVariable String sessionId) {
        ChatSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> BizException.notFound("会话不存在：" + sessionId));
        if (!session.getUserId().equals(UserContext.getUserId())) {
            throw BizException.forbidden("无权访问该会话");
        }
        return ApiResponse.ok(
                messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId));
    }
}
