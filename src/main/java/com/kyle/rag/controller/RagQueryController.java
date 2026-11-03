package com.kyle.rag.controller;

import com.kyle.rag.dto.ApiResponse;
import com.kyle.rag.dto.RagQueryRequest;
import com.kyle.rag.service.RagQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
/**
 * RAG 问答的 REST 入口(/api/v1/rag)。
 * 同步接口:请求线程内完成向量检索 + 模型生成(数秒级),流式/异步是后续演进点。
 * ⚠️ 权限校验尚未接入——kbIds 由前端传入,接入前任何用户可检索任意知识库(含私有)。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/11/4 00:38
 */
@RestController
@RequestMapping("/api/v1/rag")
@RequiredArgsConstructor
public class RagQueryController {

    private final RagQueryService ragQueryService;

    /** 问答:对 kbIds 指定的知识库做向量检索并生成回答;未命中时 answer 为引导话术 */
    @PostMapping("/query")
    public ApiResponse<String> query(@RequestBody RagQueryRequest req) {
        return ApiResponse.ok(ragQueryService.query(req.getQuestion(), req.getKbIds()));
    }
}
