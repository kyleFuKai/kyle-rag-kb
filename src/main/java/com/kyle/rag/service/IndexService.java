package com.kyle.rag.service;

import com.kyle.rag.entity.*;
import com.kyle.rag.exception.BizException;
import com.kyle.rag.repository.*;
import com.kyle.rag.security.UserContext;
import com.kyle.rag.service.loader.ParseResult;
import com.kyle.rag.service.splitter.ChunkResult;
import com.kyle.rag.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PreDestroy;
import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
/**
 * 索引任务编排服务:解析 → 分块 → 向量化 → 版本化写入的全流程。
 * 双入口:MinIO 下载(生产)/ 直接传文本(测试);失败按阶段归因,MinIO 任务带延迟重试(指数退避),
 * 重试经独立调度器延迟后由 IndexTaskLauncher 重新投递到索引线程池。
 * 用户上下文在提交任务的请求线程捕获、以参数形式传入异步链路(ThreadLocal 不跨线程)。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/10/27 11:42
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IndexService {

    /** taskType 字段值——MinIO 下载入口 */
    public static final String TASK_TYPE_FROM_MINIO = "INDEX_FROM_MINIO";
    /** taskType 字段值——直接传文本入口（测试场景，不可自动重试） */
    public static final String TASK_TYPE_FROM_TEXT = "INDEX_FROM_TEXT";

    private final KbDocumentRepository documentRepository;
    private final DocChunkRepository chunkRepository;
    private final IndexTaskRepository taskRepository;
    private final DocumentLoaderService loaderService;
    private final ChunkService chunkService;
    private final EmbeddingService embeddingService;
    private final MinioStorageService minioStorageService;
    private final IndexTaskLauncher taskLauncher;

    /**
     * 重试调度器：单线程、daemon——专用于"延迟到时间后把任务重新投递到 indexTaskExecutor"。
     * 不直接 Thread.sleep 在业务线程里——避免占用 indexTaskExecutor 的 slot，
     * 高并发失败时 sleep 会让索引线程池整体阻塞。
     */
    private final ScheduledExecutorService retryScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "index-retry-scheduler");
                t.setDaemon(true);
                return t;
            });

    /** 应用停机时释放重试调度线程;未到期的延迟重试随重启丢弃(任务行留在 PENDING,依赖人工/后续恢复机制处理) */
    @PreDestroy
    void shutdownRetryScheduler() {
        retryScheduler.shutdown();
    }

    /**
     * 提交索引任务(支持直接传入文本,测试时跳过 MinIO)。
     * 先落一条 PENDING 任务再异步投递;用户上下文必须在当前请求线程捕获、随参数传给异步链路。
     */
    public void submitIndexTask(Long docId, String textContent) {
        IndexTask task = new IndexTask();
        task.setDocId(docId);
        task.setTaskType(TASK_TYPE_FROM_TEXT);   // ★ 区分入口——scheduleRetry 据此跳过文本任务
        taskRepository.save(task);

        // 捕获当前用户上下文，传递给异步线程（ThreadLocal 不能跨线程）
        taskLauncher.launchWithText(task.getId(), docId, textContent,
                UserContext.getUserId(), UserContext.getDepartmentId(), UserContext.getRole());
    }

    /**
     * 提交索引任务(生产模式,从 MinIO 读取文件)。
     * 先落一条 PENDING 任务再异步投递;用户上下文在请求线程捕获后随参数传递。
     */
    public void submitIndexTask(Long docId) {
        IndexTask task = new IndexTask();
        task.setDocId(docId);
        task.setTaskType(TASK_TYPE_FROM_MINIO);  // ★ 区分入口——scheduleRetry 才能正确地走 MinIO 路径
        taskRepository.save(task);

        taskLauncher.launchFromMinio(task.getId(), docId,
                UserContext.getUserId(), UserContext.getDepartmentId(), UserContext.getRole());
    }

    /**
     * 从 MinIO 读取文件并执行索引（由 IndexTaskLauncher 异步调用）。
     *
     * 分阶段 try-catch 的理由：
     *   → 文档不存在：是数据被并发删除的脏请求，重试也没用，直接 markFailed 终止
     *   → MinIO 下载失败：通常是网络抖动 / 对象不存在，值得重试 → retryIfPossible
     *   → 解析/索引失败：可能是文件本身格式坏、也可能是依赖服务抖动，先重试，重试上限到了再放弃
     * 旧版本统一 catch + 写死 "从MinIO读取文件失败"——错误归因错乱、且文档不存在时会变孤儿任务。
     */
    public void executeFromMinio(Long taskId, Long docId) {
        KbDocument doc;
        // 阶段 1：取文档元数据——失败说明 docId 已被删，不重试
        try {
            doc = documentRepository.findById(docId)
                    .orElseThrow(() -> BizException.notFound("文档不存在：docId=" + docId));
        } catch (Exception e) {
            markFailed(taskId, docId, e.getMessage());
            return;   // ★ 不进入 retryIfPossible——重试也找不到这条记录
        }

        // 阶段 2：从 MinIO 下载文件——网络/IO 失败，值得重试
        byte[] fileBytes;
        try {
            fileBytes = minioStorageService.download(doc.getMinioPath());
        } catch (Exception e) {
            markFailed(taskId, docId, "从 MinIO 读取文件失败：" + e.getMessage());
            retryIfPossible(taskId, docId);
            return;
        }

        // 阶段 3：解析 + 索引——失败可能是文件格式坏，也可能是 Embedding/DB 抖动，先重试
        try {
            ParseResult parseResult = loaderService.load(
                    new ByteArrayInputStream(fileBytes), doc.getFileName());
            doIndex(taskId, docId, doc, parseResult);
            // doIndex 内部自带 try-catch，失败时已 markFailed + retry，这里 catch 兜底极端情况
        } catch (Exception e) {
            markFailed(taskId, docId, "文档解析或索引失败：" + e.getMessage());
            retryIfPossible(taskId, docId);
        }
    }

    /**
     * 执行索引（直接使用文本内容，由 IndexTaskLauncher 异步调用）。
     * 注意：文本任务失败后**不能自动重试**——文本只在内存里，重启就丢，scheduleRetry 会跳过这种 task。
     */
    public void executeWithText(Long taskId, Long docId, String textContent) {
        KbDocument doc;
        try {
            doc = documentRepository.findById(docId)
                    .orElseThrow(() -> BizException.notFound("文档不存在：docId=" + docId));
        } catch (Exception e) {
            markFailed(taskId, docId, e.getMessage());
            return;
        }
        ParseResult parseResult = ParseResult.builder()
                .success(true)
                .pages(List.of(ParseResult.PageContent.builder()
                        .pageNum(1)
                        .text(textContent)
                        .build()))
                .totalPages(1)
                .build();
        doIndex(taskId, docId, doc, parseResult);
    }

    /**
     * 核心索引逻辑：解析 → 分块 → Embedding → 写新版本 → 删旧版本 → 更新状态。
     *
     * 设计思路（关键）：**新版本写入完成后，才删除旧版本**——真正的"先写后删"。
     *   每次 doIndex 都把 doc.version + 1，新写入的 chunk 用新版本号；
     *   只有 batchInsertChunks 全部完成，才会 deleteByDocIdAndDocVersionLessThan(newVersion)。
     *   即使新数据写入中途 JVM 挂掉，旧版本数据还在，用户查询不受影响，
     *   下一次重试又会再把 version+1 重新走一遍，幂等。
     *
     * 历史版本曾经"先删旧再写新"——一旦写新挂掉，旧的也没了，用户查询直接空——已废弃。
     */
    private void doIndex(Long taskId, Long docId, KbDocument doc, ParseResult parseResult) {
        updateTaskStatus(taskId, IndexTask.TaskStatus.RUNNING);
        updateDocStatus(docId, KbDocument.DocumentStatus.PROCESSING);

        try {
            if (!parseResult.isSuccess()) {
                throw BizException.badRequest("文档解析失败：" + parseResult.getErrorMsg());
            }

            // 第一步：分块
            List<ChunkResult> chunks = chunkService.chunk(parseResult);
            if (chunks.isEmpty()) {
                throw BizException.badRequest("分块结果为空，文档可能无有效文本内容");
            }
            log.info("[IndexService] docId={}，分块完成，共{}块", docId, chunks.size());

            // 第二步：批量 Embedding
            List<String> texts = chunks.stream().map(ChunkResult::getContent).toList();
            List<float[]> embeddings = embeddingService.embedBatch(texts);

            // ★ 第三步：计算新版本号(仅内存,不落库)——新分块用新版本号写入,
            //   旧分块保持旧版本号继续对外服务;切换在第五步 save 时一次性完成,
            //   杜绝"version 已更新但新分块未写入"的检索空窗(等值过滤下文档会消失)。
            int newVersion = (doc.getVersion() == null ? 1 : doc.getVersion() + 1);
            doc.setStatus(KbDocument.DocumentStatus.PROCESSING);

            // 第四步：批量写入新版本数据
            List<DocChunk> docChunks = new ArrayList<>();
            int totalTokens = 0;
            for (int i = 0; i < chunks.size(); i++) {
                ChunkResult chunk = chunks.get(i);
                DocChunk docChunk = new DocChunk();
                docChunk.setDocId(docId);
                docChunk.setKbId(doc.getKbId());
                docChunk.setChunkIndex(chunk.getChunkIndex());
                docChunk.setContent(chunk.getContent());
                docChunk.setEmbedding(embeddings.get(i));
                docChunk.setPageNum(chunk.getPageNum());
                docChunk.setSectionTitle(chunk.getSectionTitle());
                docChunk.setTokenCount(chunk.getEstimatedTokens());
                docChunk.setDocVersion(newVersion);   // ★ 用新版本号
                docChunks.add(docChunk);
                totalTokens += chunk.getEstimatedTokens();
            }

            batchInsertChunks(docChunks);

            // ★ 第五步：原子切换——先显式 setVersion 再 save,等值过滤的检索瞬间从旧版本切到新版本,
            //   切换前全旧、切换后全新,无混合窗口;save 失败则新分块成为孤儿,
            //   旧版本仍服务,下次重建按 <> newVersion 一并清理。
            doc.setVersion(newVersion);
            doc.setStatus(KbDocument.DocumentStatus.PROCESSING);   // 维持 PROCESSING,DONE 在第六步
            documentRepository.save(doc);
            chunkRepository.deleteByDocIdAndDocVersionNot(docId, newVersion);

            // 第六步：更新文档状态(含版本号——已在上面 save,这里只更新状态字段)
            doc.setStatus(KbDocument.DocumentStatus.DONE);
            doc.setChunkCount(chunks.size());
            doc.setTokenCount(totalTokens);
            doc.setIndexedAt(LocalDateTime.now());
            documentRepository.save(doc);

            updateTaskStatus(taskId, IndexTask.TaskStatus.DONE);

            log.info("[IndexService] 索引完成：docId={}，version={}，chunks={}，tokens={}",
                    docId, newVersion, chunks.size(), totalTokens);

        } catch (Exception e) {
            log.error("[IndexService] 索引失败：docId={}，error={}", docId, e.getMessage(), e);
            markFailed(taskId, docId, e.getMessage());
            retryIfPossible(taskId, docId);
        }
    }

    /**
     * 分批写入，每批 50 条。
     * 为什么不直接 saveAll 一把梭？因为一份大文档可能有几百个 chunk，
     * 单次 INSERT 几百行对数据库的压力很大（长事务 + 大量 WAL 日志），
     * 分批写可以减少单次事务大小，也方便观察写入进度。
     */
    private void batchInsertChunks(List<DocChunk> chunks) {
        int batchSize = 50;
        for (int i = 0; i < chunks.size(); i += batchSize) {
            List<DocChunk> batch = chunks.subList(i, Math.min(i + batchSize, chunks.size()));
            chunkRepository.saveAll(batch);
            log.debug("[IndexService] 写入批次 {}/{}",
                    i / batchSize + 1, (chunks.size() + batchSize - 1) / batchSize);
        }
    }

    /** 终态化:任务置 FAILED 并写完成时间,文档同步置 FAILED、记录失败原因;文档已被并发删除时跳过 */
    private void markFailed(Long taskId, Long docId, String errorMsg) {
        IndexTask task = taskRepository.findById(taskId).orElseThrow();
        task.setStatus(IndexTask.TaskStatus.FAILED);
        task.setErrorMsg(errorMsg);
        task.setFinishedAt(LocalDateTime.now());
        taskRepository.save(task);

        documentRepository.findById(docId).ifPresent(doc -> {
            doc.setStatus(KbDocument.DocumentStatus.FAILED);
            doc.setErrorMsg(errorMsg);
            documentRepository.save(doc);
        });
    }

    /** 未达重试上限则计数 +1、任务回到 PENDING 并调度延迟重试;已达上限保持 FAILED 终态,不再投递 */
    private void retryIfPossible(Long taskId, Long docId) {
        IndexTask task = taskRepository.findById(taskId).orElseThrow();
        if (task.canRetry()) {
            task.setRetryCount(task.getRetryCount() + 1);
            task.setStatus(IndexTask.TaskStatus.PENDING);
            taskRepository.save(task);
            log.info("[IndexService] 任务将重试：taskId={}，retryCount={}", taskId, task.getRetryCount());
            // 延迟重试（指数退避：1s, 2s, 4s）
            scheduleRetry(taskId, docId, task.getRetryCount());
        }
    }

    /**
     * 延迟重试——指数退避（1s → 2s → 4s …）。
     *
     * 关键设计：
     *   1. 用独立的 retryScheduler 延迟、**不在 indexTaskExecutor 线程里 Thread.sleep**——
     *      否则多个失败任务并发 sleep 会把索引线程池整体阻塞。
     *   2. 时间到了通过 taskLauncher 把任务**重新投递到 indexTaskExecutor**——
     *      走 @Async 代理，UserContext 在新线程里重新 set。
     *   3. 根据 task.taskType **正确选择重投入口**——
     *      MinIO 任务走 launchFromMinio，文本任务（文本只在内存里）直接放弃自动重试。
     */
    protected void scheduleRetry(Long taskId, Long docId, int retryCount) {
        IndexTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null) {
            log.warn("[IndexService] 重试时找不到 task：taskId={}", taskId);
            return;
        }

        // ★ 文本任务无法自动重试——文本只在内存里，无法持久化
        if (TASK_TYPE_FROM_TEXT.equals(task.getTaskType())) {
            log.warn("[IndexService] 文本任务（taskType={}）不支持自动重试：taskId={}",
                    task.getTaskType(), taskId);
            return;
        }

        // 在业务线程里**先捕获** UserContext——延迟回调时 ThreadLocal 已被清空
        Long userId = UserContext.getUserId();
        String departmentId = UserContext.getDepartmentId();
        String role = UserContext.getRole();
        long delaySeconds = (long) Math.pow(2, retryCount - 1);

        retryScheduler.schedule(() -> {
            try {
                // ★ 通过 taskLauncher 重新投递——走 @Async 代理 → indexTaskExecutor 起新线程
                //   不要直接 executeFromMinio()，否则会跑在 retryScheduler 的单线程上
                taskLauncher.launchFromMinio(taskId, docId, userId, departmentId, role);
            } catch (Exception e) {
                log.error("[IndexService] 重投递任务失败：taskId={}, err={}", taskId, e.getMessage(), e);
            }
        }, delaySeconds, TimeUnit.SECONDS);
    }

    /** 更新任务状态;置 RUNNING 写开始时间、置 DONE 写结束时间;任务不存在时静默跳过 */
    private void updateTaskStatus(Long taskId, IndexTask.TaskStatus status) {
        taskRepository.findById(taskId).ifPresent(t -> {
            t.setStatus(status);
            if (status == IndexTask.TaskStatus.RUNNING) t.setStartedAt(LocalDateTime.now());
            if (status == IndexTask.TaskStatus.DONE)    t.setFinishedAt(LocalDateTime.now());
            taskRepository.save(t);
        });
    }

    /** 更新文档索引状态;文档已被并发删除时静默跳过 */
    private void updateDocStatus(Long docId, KbDocument.DocumentStatus status) {
        documentRepository.findById(docId).ifPresent(d -> {
            d.setStatus(status);
            documentRepository.save(d);
        });
    }
}
