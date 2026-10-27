package com.kyle.rag.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

/**
 * 文本向量化服务：先查询 Redis 缓存，将未命中文本提交给模型，再按原始输入顺序返回向量。
 * 文档分块和用户提问共用该流程；向量的维度由模型配置决定，向量入库由下游业务负责。
 *
 * 缓存保存文本到向量的映射，TokenMetrics 记录 API 返回的用量；纯缓存命中不会新增 API 用量。
 * 当前 Redis 读写异常会向上抛出；缓存内容无法解析为数字时才按未命中重新计算。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/10/25 15:23
 */
@Service
@Slf4j
public class EmbeddingService {

    /** 模型调用入口，实际使用构造器指定的 OpenAI 兼容客户端连接百炼。 */
    private final EmbeddingModel embeddingModel;
    /** 以字符串形式缓存向量，与 serializeVector / deserializeVector 的格式保持一致。 */
    private final StringRedisTemplate redisTemplate;
    /** 将本次 API 用量上报到应用监控及用户统计。 */
    private final TokenMetrics tokenMetrics;

    /**
     * 自身的延迟代理引用，内部调用经此入口触发 Spring Retry 的 AOP 拦截。
     * {@code @Lazy} 推迟解析自身 Bean，避免构造当前对象时立即解析自己；它不表示异步执行。
     */
    private final EmbeddingService self;

    /**
     * 显式选择 OpenAI 兼容客户端，其实际服务地址由配置指向百炼。
     * 容器同时存在 MiniMax 向量模型时，通过 Bean 名称消除注入歧义。
     */
    public EmbeddingService(@Qualifier("openAiEmbeddingModel") EmbeddingModel embeddingModel,
                            StringRedisTemplate redisTemplate,
                            TokenMetrics tokenMetrics,
                            @Lazy EmbeddingService self) {
        this.embeddingModel = embeddingModel;
        this.redisTemplate = redisTemplate;
        this.tokenMetrics = tokenMetrics;
        this.self = self;
    }

    /** 缓存命名空间版本；当前 key 未包含模型及维度，切换模型配置时应同步调整命名空间。 */
    private static final String CACHE_PREFIX = "emb:v1:";

    /** 新生成向量写入 Redis 时的有效期；读取命中缓存时不会在这里续期。 */
    @Value("${rag.cache.embedding-ttl:7d}")
    private Duration embeddingTtl;

    /**
     * 单次模型请求的文本条数上限。
     * 当前设为 10，与配置的 text-embedding-v4 单批上限一致；切换模型时需核对对应限制。
     */
    private static final int BATCH_SIZE = 10;

    /**
     * 批量向量化：查缓存、计算未命中项、回填缓存，最后恢复输入顺序。
     * 调用方应保证列表中的文本非 null 且非空白；当前只对列表本身做空值和空列表检查。
     *
     * @param texts 待向量化的文本列表
     * @return 成功时与输入位置一一对应的向量列表；列表为空或为 null 时返回空列表
     */
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) return List.of();

        // key 是 texts 的原始下标：先保存缓存命中结果，后续再补齐 API 新生成的向量。
        Map<Integer, float[]> cached = new HashMap<>();
        // 两个列表按位置一一对应：missedTexts[j] 在原输入中的位置是 missedIndices[j]。
        // 例如 [A, B, C, D] 只有 B、D 未命中，则分别记录 [1, 3] 和 [B, D]。
        List<Integer> missedIndices = new ArrayList<>(); //缓存未命中
        List<String> missedTexts = new ArrayList<>(); //对应的文本

        for (int i = 0; i < texts.size(); i++) {
            String cacheKey = buildCacheKey(texts.get(i)); //MD5的方式算出缓存的key
            String cachedStr = redisTemplate.opsForValue().get(cacheKey);
            if (cachedStr != null) {
                try {
                    cached.put(i, deserializeVector(cachedStr));
                } catch (NumberFormatException ex) {
                    // 数字解析失败时删除该缓存，并登记为未命中项，后续统一向模型请求。
                    // 此处只捕获 NumberFormatException，Redis 连接或删除失败仍会向上抛出。
                    log.warn("[Embedding] 缓存数据损坏，回退到 API：key={}, err={}",
                            cacheKey, ex.getMessage());
                    redisTemplate.delete(cacheKey);
                    missedIndices.add(i);
                    missedTexts.add(texts.get(i));
                }
            } else {
                missedIndices.add(i);
                missedTexts.add(texts.get(i));
            }
        }

        log.debug("[Embedding] 总数={}，缓存命中={}，需要调API={}",
                texts.size(), cached.size(), missedTexts.size());

        if (!missedTexts.isEmpty()) {
            // 全部命中时跳过 API；存在未命中项时，经 self 代理进入重试拦截。
            // 直接通过 this 调用同类方法会绕过 Spring AOP 代理。
            List<float[]> newVectors = self.embedFromApi(missedTexts);

            for (int j = 0; j < missedIndices.size(); j++) {
                // j 是未命中列表的位置，originalIndex 才是该文本在完整输入中的位置。
                // 此映射依赖 API 为每条未命中文本返回一个有效向量，当前尚未校验结果数量。
                int originalIndex = missedIndices.get(j);
                float[] vector = newVectors.get(j);
                cached.put(originalIndex, vector);

                String cacheKey = buildCacheKey(texts.get(originalIndex));
                redisTemplate.opsForValue().set(cacheKey, serializeVector(vector), embeddingTtl);
            }
        }

        // 按 0..size-1 逐个取结果，避免依赖 HashMap 的遍历顺序而错配文本与向量。
        return IntStream.range(0, texts.size())
                .mapToObj(cached::get)
                .toList();
    }

    /**
     * 顺序调用 Embedding API，将各批结果按输入顺序拼接成一个列表。
     *
     * 当前外层重试作用于整个方法：匹配重试策略时最多尝试 3 次（包含首次），间隔为 1 秒、2 秒。
     * 后续批次失败会从首批重新执行，可能重复已经成功的请求。
     * 模型实现内部也可能重试并转换异常类型，因此此注解不能代表实际 HTTP 请求的总尝试次数，
     * 也不能据此保证所有 5xx 或 429 都按外层策略重试。
     *
     * @param texts 需要调用模型的文本，通常由 embedBatch 筛选出未命中缓存的文本后传入
     * @return 按 texts 顺序排列的向量列表
     */
    @Retryable(
            retryFor = {
                    ResourceAccessException.class,        // 网络 IO 异常（连接超时、读超时、Connection reset）
                    HttpServerErrorException.class,        // 5xx 服务端错误（502/503/504）
                    java.util.concurrent.TimeoutException.class
            },
            noRetryFor = HttpClientErrorException.class,   // 此类型不再重试；是否进入恢复方法由恢复策略决定
            maxAttempts = 3,
            backoff = @Backoff(delay = 1000, multiplier = 2)
    )
    public List<float[]> embedFromApi(List<String> texts) {
        List<float[]> result = new ArrayList<>();
        // 仅累计本次方法执行取得的用量；各批次在当前线程中顺序调用，并未并行提交。
        AtomicInteger totalTokens = new AtomicInteger(0);

        // 每批采用左闭右开区间 [start, end)，最后一批通过 min 截断，避免超过列表末尾。
        for (int start = 0; start < texts.size(); start += BATCH_SIZE) {
            int end = Math.min(start + BATCH_SIZE, texts.size());
            List<String> batch = texts.subList(start, end);

            long batchStart = System.currentTimeMillis();
            // null 表示不传入本次专用 options，由模型实现合并使用默认模型等配置。
            EmbeddingResponse response = embeddingModel.call(
                    new EmbeddingRequest(batch, null));
            long elapsed = System.currentTimeMillis() - batchStart;

            // 仅累计服务端返回的 usage；缺失时不按文本长度补估 Token 数。
            if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
                long tokens = response.getMetadata().getUsage().getTotalTokens();
                totalTokens.addAndGet((int) tokens);
            }

            // index 对应本批输入的位置：先排序，再将 float[] 追加到各批共享的结果列表。
            // 当前依赖服务端返回完整且有效的 index，尚未校验重复、缺失或越界情况。
            response.getResults().stream()
                    .sorted(Comparator.comparingInt(r -> r.getIndex()))
                    .forEach(r -> result.add(r.getOutput()));

            // 当前批次从 1 开始计数，总批次数采用整数运算向上取整。
            log.debug("[Embedding] 批次{}/{}，size={}，耗时={}ms",
                    start / BATCH_SIZE + 1,
                    (texts.size() + BATCH_SIZE - 1) / BATCH_SIZE,
                    batch.size(), elapsed);
        }

        log.info("[Embedding] API调用完成，共{}条，消耗Token={}",
                texts.size(), totalTokens.get());

        // 所有批次成功后统一上报；中途失败时，本次执行已产生的用量不会在这里记录。
        if (totalTokens.get() > 0) {
            tokenMetrics.recordEmbeddingTokens(totalTokens.get());
        }

        return result;
    }

    /**
     * 匹配此恢复方法的异常，在重试结束或被判定为不重试后交由这里处理。
     * 处理方式是记录错误、包装原异常并继续抛出，本次向量化仍然失败。
     *
     * noRetryFor 仅控制是否再次尝试，不等同于 notRecoverable；当前 Exception 参数匹配范围较广。
     * 因此进入本方法不代表一定尝试了 3 次，下面日志及异常文本中的固定次数不宜作为调用次数依据。
     */
    @Recover
    public List<float[]> embedFromApiFallback(Exception e, List<String> texts) {
        log.error("[Embedding] 重试3次后仍失败，texts.size={}，error={}",
                texts.size(), e.getMessage());
        throw new RuntimeException("Embedding API 调用失败，已重试3次：" + e.getMessage(), e);
    }

    /**
     * 单条向量化入口，查询文本复用与文档文本相同的缓存及模型调用流程。
     * 空结果分支返回空数组；请求异常会继续向外抛出，不由该分支兜底。
     *
     * @param text 待向量化的文本，调用方应保证非空白
     * @throws NullPointerException text 为 null 时，List.of 无法构造单元素列表
     */
    public float[] embed(String text) {
        List<float[]> result = embedBatch(List.of(text));
        return result.isEmpty() ? new float[0] : result.get(0);
    }

    /** 原文的空格、换行等字符均参与摘要计算；摘要仅用于定位缓存，语义向量由模型生成。 */
    private String buildCacheKey(String text) {
        return CACHE_PREFIX + toMd5(text);
    }

    /** 正常路径使用 UTF-8 字节计算 MD5，并转换为 32 个十六进制字符。 */
    private String toMd5(String text) {
        try {
            var md = java.security.MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            // 此分支保留原有字符串哈希兜底，其算法及碰撞概率与 MD5 不同。
            return String.valueOf(text.hashCode());
        }
    }

    /**
     * 将 float[] 编码为逗号分隔的数字文本，例如 {@code 0.1,-0.2,0.3}。
     * 由 StringRedisTemplate 写入，读取时使用 deserializeVector 恢复为数组。
     */
    private String serializeVector(float[] vector) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vector[i]);
        }
        return sb.toString();
    }

    /**
     * 先去除可选的中括号及普通空格，再逐项解析数字，兼容 {@code [0.1, -0.2]} 形式。
     * 当前只做数字解析，尚未校验向量维度、NaN 或无穷值。
     *
     * @throws NumberFormatException 某个分量无法解析为 float，调用方据此将缓存视为未命中
     */
    private float[] deserializeVector(String str) {
        str = str.replace("[", "").replace("]", "").replace(" ", "");
        String[] parts = str.split(",");
        float[] vector = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            vector[i] = Float.parseFloat(parts[i]);
        }
        return vector;
    }
}
