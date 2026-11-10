package com.kyle.rag.controller;

import com.kyle.rag.dto.ApiResponse;
import com.kyle.rag.dto.RagQueryRequest;
import jakarta.validation.Valid;
import com.kyle.rag.dto.RagResponse;
import com.kyle.rag.service.FullRagPipeline;
import com.kyle.rag.service.PermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
/**
 * RAG 问答的 REST 入口(/api/v1/rag)。
 * 同步接口:请求线程内完成向量检索 + 模型生成(数秒级);kbIds 由前端指定要检索的知识库。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/11/4 00:38
 */
@RestController
@RequestMapping("/api/v1/rag")
@RequiredArgsConstructor
public class RagQueryController {

    private final PermissionService permissionService;
    private final FullRagPipeline fullRagPipeline;

    /** 问答:对 kbIds 指定的知识库做向量检索并生成回答;未命中时 answer 为引导话术 */
    @PostMapping("/query")
    public ApiResponse<RagResponse> query(@Valid @RequestBody RagQueryRequest req) {
        // 逐个知识库校验读权限:与 /api/v1/chat 同一口径——请求了无权访问的库就明确 403,
        // 而不是静默过滤掉让人困惑。管道层另有一次过滤,作为内部调用的防御纵深。
        req.getKbIds().forEach(permissionService::requireRead);
        return ApiResponse.ok(fullRagPipeline.query(req.getQuestion(), req.getKbIds()));
    }
}
