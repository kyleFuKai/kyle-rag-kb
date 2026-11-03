package com.kyle.rag.service;

import com.kyle.rag.entity.KbDocument;
import com.kyle.rag.repository.KbDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
/**
 * 文档更新的事务边界 Service。
 *
 * → Spring @Transactional 基于 AOP 代理，"同一 Bean 内方法互调"会绕过代理 → 事务注解失效
 * → 把事务方法放到独立 Bean，让编排方法跨 Bean 调用——代理生效，事务才真启动并提交
 *
 * 使用契约（编排层，如 KnowledgeBaseService / Controller）：
 *   1. 先调用本类事务方法（方法返回时事务已提交，记录对其他连接可见）
 *   2. 之后再调用 IndexService.submitIndexTask 投递异步索引
 * 顺序不能反：先投递的话索引线程可能读到未提交的数据（事务竞态）。
 * 本类只管"库内记录 + MinIO 新文件"，不做版本清理与索引执行——那是 doIndex 的职责。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/11/3 22:55
 */

@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentTxService {

    private final KbDocumentRepository documentRepository;
    private final MinioStorageService minioService;

    /**
     * 替换整个文档(情况 1):上传新文件到 MinIO + 更新文档记录,一个事务内完成。
     * 文件名/大小/路径随新文件更新;版本号 +1、状态回 PENDING、清空上轮统计字段
     * (避免轮询接口在重建完成前显示旧值)。
     * 返回旧文件路径,供编排层在新版本索引成功后再删除旧文件——不要在事务里删:
     * 旧版本分块此刻仍在对外服务,删除后一旦索引失败将无法重建。
     */
    @Transactional
    public String updateDocumentRecord(Long docId, MultipartFile newFile) {
        KbDocument doc = documentRepository.findById(docId)
                .orElseThrow(() -> new RuntimeException("文档不存在：" + docId));

        String oldMinioPath = doc.getMinioPath();
        String newMinioPath = minioService.upload(doc.getKbId(), newFile);

        doc.setFileName(newFile.getOriginalFilename());
        doc.setFileSize(newFile.getSize());
        doc.setMinioPath(newMinioPath);
        doc.setVersion(doc.getVersion() + 1);
        doc.setStatus(KbDocument.DocumentStatus.PENDING);
        doc.setErrorMsg(null);
        // 重建索引前清理上一轮的统计字段——避免前端轮询时看到旧值产生"已索引完"的误解
        doc.setChunkCount(null);
        doc.setTokenCount(null);
        doc.setIndexedAt(null);
        documentRepository.save(doc);

        log.info("[DocumentTx] 文档记录更新：docId={}，newVersion={}，newFile={}",
                docId, doc.getVersion(), newFile.getOriginalFilename());
        return oldMinioPath;
    }

    /**
     * 强制重建索引(文件未变,只想用新策略重新切分/向量化):版本号 +1、状态回 PENDING、清统计字段。
     * 与 updateDocumentRecord 的区别:不动 MinIO 文件,只重置索引生命周期。
     */
    @Transactional
    public void forceReindex(Long docId) {
        KbDocument doc = documentRepository.findById(docId)
                .orElseThrow(() -> new RuntimeException("文档不存在：" + docId));

        doc.setVersion(doc.getVersion() + 1);
        doc.setStatus(KbDocument.DocumentStatus.PENDING);
        doc.setErrorMsg(null);
        // 同样清理统计字段
        doc.setChunkCount(null);
        doc.setTokenCount(null);
        doc.setIndexedAt(null);
        documentRepository.save(doc);

        log.info("[DocumentTx] 强制重建版本递增：docId={}，newVersion={}", docId, doc.getVersion());
    }
}
