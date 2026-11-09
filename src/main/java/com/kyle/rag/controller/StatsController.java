package com.kyle.rag.controller;

import com.kyle.rag.dto.ApiResponse;
import com.kyle.rag.security.UserContext;
import com.kyle.rag.service.TokenMetrics;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
/**
 * 用户级成本统计的 REST 入口(/api/v1/stats):三维度 Token 消耗 + 按参考定价的金额估算。
 * 定价是写死的参考值(与实际所用模型 MiniMax/qwen 的计费口径不完全一致),金额仅供横向比较。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/11/10 01:02
 */
@RestController
@RequestMapping("/api/v1/stats")
@RequiredArgsConstructor
public class StatsController {

    private final TokenMetrics tokenMetrics;

    /** 当前用户三维度 Token 统计 + 估算成本(元);Redis 持久化,重启不丢 */
    @GetMapping("/tokens")
    public ApiResponse<Map<String, Object>> getTokenStats() {
        Long userId = UserContext.getUserId();

        long embeddingTokens = tokenMetrics.getUserTokens(userId, "embeddingTokens");
        long contextTokens = tokenMetrics.getUserTokens(userId, "contextTokens");
        long generationTokens = tokenMetrics.getUserTokens(userId, "generationTokens");
        long totalTokens = embeddingTokens + contextTokens + generationTokens;

        // 简单成本估算（DashScope 定价，仅供参考）
        // text-embedding-v3: ¥0.0007 / 1000 Token
        // qwen-plus 输入: ¥0.0008 / 1000 Token
        // qwen-plus 输出: ¥0.002 / 1000 Token
        double estimatedCostCny =
                embeddingTokens / 1000.0 * 0.0007
                        + contextTokens / 1000.0 * 0.0008
                        + generationTokens / 1000.0 * 0.002;

        return ApiResponse.ok(Map.of(
                "embeddingTokens", embeddingTokens,
                "contextTokens", contextTokens,
                "generationTokens", generationTokens,
                "totalTokens", totalTokens,
                "estimatedCostCny", Math.round(estimatedCostCny * 10000) / 10000.0
        ));
    }
}
