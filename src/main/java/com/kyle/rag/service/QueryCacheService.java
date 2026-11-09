package com.kyle.rag.service;

import com.kyle.rag.dto.RagResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
/**
 * 查询结果缓存服务:相同问题 + 相同知识库集合 → 缓存 10 分钟,命中后跳过检索与生成。
 * 缓存键 = MD5(question + 排序后的 kbIds),kbIds 排序保证同一集合的 key 一致;
 * notFound 结果不缓存(文档更新后可能命中)。仅接入流式链路且首轮生效(多轮追问不走缓存)。
 *
 * 适用场景：同一部门内，相同问题被多人反复问（FAQ 类场景命中率高）。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/11/10 00:58
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QueryCacheService {

    private final RedisTemplate<String, Object> redisTemplate;

    private static final String CACHE_PREFIX = "rag:query:";
    private static final Duration QUERY_TTL = Duration.ofMinutes(10);

    /** 查缓存,命中返回完整 RagResponse,未命中返回 null */
    public RagResponse getFromCache(String question, List<Long> kbIds) {
        String key = buildKey(question, kbIds);
        Object cached = redisTemplate.opsForValue().get(key);
        if (cached instanceof RagResponse resp) {
            log.info("[QueryCache] 命中缓存：question={}", question.substring(0, Math.min(30, question.length())));
            return resp;
        }
        return null;
    }

    /** 写缓存;notFound 响应直接跳过 */
    public void putToCache(String question, List<Long> kbIds, RagResponse response) {
        // 无效答案不缓存（notFound 的结果可能因为文档更新而变化）
        if (response.isNotFound()) return;

        String key = buildKey(question, kbIds);
        redisTemplate.opsForValue().set(key, response, QUERY_TTL);
        log.debug("[QueryCache] 写入缓存：key={}", key);
    }

    /** 缓存键:rag:query:{MD5(question:排序kbIds)} */
    private String buildKey(String question, List<Long> kbIds) {
        // 排序 kbIds 保证相同知识库集合的 key 一致
        List<Long> sortedIds = kbIds.stream().sorted().toList();
        return CACHE_PREFIX + toMd5(question + ":" + sortedIds);
    }

    private String toMd5(String text) {
        try {
            var md = java.security.MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(text.hashCode());
        }
    }
}
