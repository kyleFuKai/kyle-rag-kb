package com.kyle.rag.controller;

import com.kyle.rag.dto.*;
import com.kyle.rag.exception.BizException;
import jakarta.validation.Valid;
import com.kyle.rag.entity.*;
import com.kyle.rag.repository.*;
import com.kyle.rag.security.UserContext;
import com.kyle.rag.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
/**
 * 知识库与文档的 REST 入口(/api/v1/kb)。
 * 所有 kb 级操作先过 PermissionService(requireRead/requireWrite),再进 service 层;
 * 上传是异步语义:接口立即返回 docId+PENDING,前端凭 docId 轮询 /status。
 * 路径里的 kbId 与 docId 必须归属一致,校验逻辑见各方法。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/11/3 14:48
 */
@RestController
@RequestMapping("/api/v1/kb")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final PermissionService permissionService;
    private final KnowledgeBaseService kbService;
    private final DocumentUpdateService documentUpdateService;
    private final KbDocumentRepository documentRepository;
    private final IndexTaskRepository taskRepository;
    private final MinioStorageService minioService;

    /** 查询当前用户可访问的知识库列表(含权限级别);无需权限校验,数据本身按用户可见性过滤 */
    @GetMapping
    public ApiResponse<List<KnowledgeBaseVO>> list() {
        return ApiResponse.ok(kbService.listAccessible());
    }

    /** 创建知识库;创建者在 service 内自动获得该库的 ADMIN 授权 */
    @PostMapping
    public ApiResponse<KnowledgeBase> create(@Valid @RequestBody KnowledgeBaseCreateRequest req) {
        return ApiResponse.ok(kbService.create(req));
    }

    /** 上传文档到知识库(需 WRITE);异步索引,立即返回 docId + PENDING 供前端轮询 /status */
    @PostMapping("/{kbId}/documents")
    public ApiResponse<DocumentUploadResponse> upload(
            @PathVariable Long kbId,
            @RequestParam("file") MultipartFile file) {
        permissionService.requireWrite(kbId);
        KbDocument doc = kbService.uploadDocument(kbId, file);
        return ApiResponse.ok(DocumentUploadResponse.submitted(doc.getId(), doc.getFileName()));
    }

    /** 查询文档索引状态(READ,前端轮询用);retryCount 取该文档最新一条任务的值(含重试场景) */
    @GetMapping("/{kbId}/documents/{docId}/status")
    public ApiResponse<IndexStatusResponse> getStatus(
            @PathVariable Long kbId,
            @PathVariable Long docId) {
        permissionService.requireRead(kbId);

        KbDocument doc = documentRepository.findById(docId)
                .orElseThrow(() -> BizException.notFound("文档不存在"));

        // 查最新的索引任务（可能有重试）
        IndexTask latestTask = taskRepository
                .findTopByDocIdOrderByCreatedAtDesc(docId)
                .orElse(null);

        IndexStatusResponse resp = new IndexStatusResponse();
        resp.setDocId(doc.getId());
        resp.setFileName(doc.getFileName());
        resp.setStatus(doc.getStatus().name());
        resp.setErrorMsg(doc.getErrorMsg());
        resp.setChunkCount(doc.getChunkCount());
        resp.setTokenCount(doc.getTokenCount());
        resp.setIndexedAt(doc.getIndexedAt() != null ? doc.getIndexedAt().toString() : null);
        resp.setRetryCount(latestTask != null ? latestTask.getRetryCount() : 0);
        return ApiResponse.ok(resp);
    }

    /** 查询知识库的文档列表(READ);已删文档由 @SoftDelete 自动过滤 */
    @GetMapping("/{kbId}/documents")
    public ApiResponse<List<KbDocument>> listDocuments(@PathVariable Long kbId) {
        permissionService.requireRead(kbId);
        List<KbDocument> docs = documentRepository.findByKbId(kbId);   // @SoftDelete 自动过滤已删行
        return ApiResponse.ok(docs);
    }

    /** 删除文档(需 WRITE);软删文档记录 + 硬删分块,MinIO 原件尽力而为删除,细节在 service */
    @DeleteMapping("/{kbId}/documents/{docId}")
    public ApiResponse<Void> deleteDocument(
            @PathVariable Long kbId,
            @PathVariable Long docId) {
        permissionService.requireWrite(kbId);
        kbService.deleteDocument(docId);
        return ApiResponse.ok(null);
    }

    /** 下载原始文件(READ);文件名按 RFC 5987 编码(支持中文,+ 转空格) */
    @GetMapping("/{kbId}/documents/{docId}/download")
    public ResponseEntity<byte[]> download(
            @PathVariable Long kbId,
            @PathVariable Long docId) {
        permissionService.requireRead(kbId);
        KbDocument doc = documentRepository.findById(docId)
                .orElseThrow(() -> BizException.notFound("文档不存在"));
        byte[] content = minioService.download(doc.getMinioPath());
        String encodedName = URLEncoder.encode(doc.getFileName(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + encodedName)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(content);
    }

    /** 重建索引(需 WRITE);异步执行,完成后旧版本分块由 IndexService 按版本号清理 */
    @PostMapping("/{kbId}/documents/{docId}/reindex")
    public ApiResponse<String> reindex(
            @PathVariable Long kbId,
            @PathVariable Long docId) {
        permissionService.requireWrite(kbId);
        requireDocumentInKb(kbId, docId);
        documentUpdateService.forceReindexAndSubmit(docId);
        return ApiResponse.ok("重建索引任务已提交，请通过 /status 接口查询进度");
    }

    /** 归属校验:docId 必须属于路径中的 kbId,防止跨库越权操作(消息/文档类端点通用) */
    private void requireDocumentInKb(Long kbId, Long docId) {
        KbDocument doc = documentRepository.findById(docId)
                .orElseThrow(() -> com.kyle.rag.exception.BizException.notFound("文档不存在"));
        if (!doc.getKbId().equals(kbId)) {
            throw com.kyle.rag.exception.BizException.notFound("文档不存在");
        }
    }
}