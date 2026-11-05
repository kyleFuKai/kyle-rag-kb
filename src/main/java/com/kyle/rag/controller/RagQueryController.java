package com.kyle.rag.controller;

import com.kyle.rag.dto.ApiResponse;
import com.kyle.rag.dto.RagQueryRequest;
import com.kyle.rag.dto.RagResponse;
import com.kyle.rag.service.FullRagPipeline;
import com.kyle.rag.service.RagQueryServiceV3;
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

    private final FullRagPipeline fullRagPipeline;

    /** 问答:对 kbIds 指定的知识库做向量检索并生成回答;未命中时 answer 为引导话术 */
    @PostMapping("/query")
    public ApiResponse<RagResponse> query(@RequestBody RagQueryRequest req) {
        return ApiResponse.ok(fullRagPipeline.query(req.getQuestion(), req.getKbIds()));
    }
}
