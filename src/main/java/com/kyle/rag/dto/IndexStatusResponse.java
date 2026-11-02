package com.kyle.rag.dto;

/**
 * 文档索引状态查询的响应体,供上传后的轮询(与 DocumentUploadResponse 配套)。
 * status 取值同 KbDocument.DocumentStatus:索引任务完成前 chunkCount 等统计字段为初始值。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/11/2 15:20
 */
public class IndexStatusResponse {

    /** 文档 ID */
    private Long docId;

    /** 原始文件名回显 */
    private String fileName;

    /** 索引状态:PENDING / PROCESSING / DONE / FAILED */
    private String status;

    /** 失败原因,仅 FAILED 有值 */
    private String errorMsg;

    /** 已完成的分块数量 */
    private Integer chunkCount;

    /** 向量化消耗的 Token 总数 */
    private Integer tokenCount;

    /** 最近一次索引完成时间 */
    private String indexedAt;

    /** 已重试次数 */
    private Integer retryCount;
}
