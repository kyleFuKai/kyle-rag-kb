package com.kyle.rag.dto;

import lombok.Data;
/**
 * 文档上传接口的响应体。上传是异步的:接口立即返回 docId 与 PENDING,索引在后台进行,
 * 客户端凭 docId 轮询文档状态。status 取值同 KbDocument.DocumentStatus。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/11/2 15:07
 */
@Data
public class DocumentUploadResponse {

    /** 新建文档的 ID,后续轮询状态、重建索引都凭它 */
    private Long docId;

    /** 原始文件名回显 */
    private String fileName;

    /** 当前索引状态;上传刚完成时恒为 PENDING(已提交,等待索引) */
    private String status;

    /** 给前端的提示文案 */
    private String message;

    /** 工厂:文件已存、任务已提交时的标准响应 */
    public static DocumentUploadResponse submitted(Long docId, String fileName) {
        DocumentUploadResponse r = new DocumentUploadResponse();
        r.setDocId(docId);
        r.setFileName(fileName);
        r.setStatus("PENDING");
        r.setMessage("文档已上传，正在后台索引，请通过 /status 接口查询进度");
        return r;
    }
}
