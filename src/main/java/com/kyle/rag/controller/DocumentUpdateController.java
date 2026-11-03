package com.kyle.rag.controller;

import com.kyle.rag.dto.ApiResponse;
import com.kyle.rag.entity.KbDocument;
import com.kyle.rag.service.DocumentUpdateService;
import com.kyle.rag.service.PermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
/**
 * 文档更新流程的 REST 入口(/api/v1/kb 下):整份替换内容、强制重建索引。
 * 两个端点都要求 WRITE 权限;核心编排在 DocumentUpdateService(事务提交后再投递异步索引),
 * 端点只做权限前置与转发。响应里的 status 为 PENDING,进度凭 docId 轮询 status 接口。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/11/3 23:36
 */
@RestController
@RequestMapping("/api/v1/kb")
@RequiredArgsConstructor
public class DocumentUpdateController {

    private final PermissionService permissionService;
    private final DocumentUpdateService documentUpdateService;

    /** 替换文档内容(需 WRITE,docId 不变):新文件入库 + 版本递增 + 异步索引;返回更新后的文档记录 */
    @PutMapping("/{kbId}/documents/{docId}/content")
    public ApiResponse<KbDocument> replaceContent(
            @PathVariable Long kbId,
            @PathVariable Long docId,
            @RequestParam("file") MultipartFile file) {
        permissionService.requireWrite(kbId);
        return ApiResponse.ok(documentUpdateService.replaceDocument(docId, file));
    }

    /**
     * 强制重建索引(需 WRITE):文件字节不变,按当前解析/分块策略重新走索引流程,异步执行。
     * 与"上传后首次索引"的区别在于这是显式触发,用于策略调优后刷新既有文档。
     */
    @PostMapping("/{kbId}/documents/{docId}/reindex-force")
    public ApiResponse<Void> forceReindex(
            @PathVariable Long kbId,
            @PathVariable Long docId) {
        permissionService.requireWrite(kbId);
        documentUpdateService.forceReindexAndSubmit(docId);
        return ApiResponse.ok(null);
    }
}
