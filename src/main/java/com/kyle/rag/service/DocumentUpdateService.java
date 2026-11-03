package com.kyle.rag.service;

import com.kyle.rag.entity.KbDocument;
import com.kyle.rag.repository.KbDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
/**
 * 文档更新的编排 Service。
 * 自身不放任何事务——所有事务边界都在 DocumentTxService 里。
 *
 * 编排顺序：
 * 1. 跨 Bean 调用 txService 的事务方法——事务在那一刻完整启动并提交
 * 2. 事务提交后再触发异步索引——保证异步线程从 DB 能读到最新数据
 * 3. 最后清理旧的 MinIO 文件——异步任务读的是新 minioPath，与旧文件无关
 *
 * @author kyle
 * @version 1.0
 * @date 2026/11/3 23:11
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentUpdateService {

    private final DocumentTxService txService;           // ★ 跨 Bean 调用——@Transactional 才生效
    private final KbDocumentRepository documentRepository;
    private final MinioStorageService minioService;
    private final IndexService indexService;

    /**
     * 替换文档内容,保持文档 ID 不变。编排三步:事务更新记录 → 提交后投递异步索引 → 删除旧文件。
     * 旧文件删除是尽力而为(MinIO delete 内部吞异常只记日志,失败不影响已提交的结果);
     * 删除后即失去回滚到旧版本的能力,异步任务重建用的是新 minioPath,不依赖旧文件。
     */
    public KbDocument replaceDocument(Long docId, MultipartFile newFile) {
        // ★ 跨 Bean 调用：代理生效、事务真正启动并提交
        String oldMinioPath = txService.updateDocumentRecord(docId, newFile);

        // ★ 事务已提交，异步线程从 DB 能读到新的 minioPath
        indexService.submitIndexTask(docId);

        // ★ 异步任务读的是新 minioPath，旧文件可以安全删除
        minioService.delete(oldMinioPath);

        return documentRepository.findById(docId).orElseThrow();
    }

    /**
     * 强制重建索引(文件字节未变,解析/分块/向量化策略变了等场景):重置索引生命周期后投递,不动 MinIO 文件。
     */
    public void forceReindexAndSubmit(Long docId) {
        txService.forceReindex(docId);                   // ★ 跨 Bean 调用
        indexService.submitIndexTask(docId);
        log.info("[DocumentUpdate] 强制重建索引已提交：docId={}", docId);
    }
}
