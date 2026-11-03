package com.kyle.rag.dto;

import lombok.Data;

import java.util.List;
/**
 * RAG 问答的请求体。kbIds 为空或 question 为空时不报错,
 * 由 service 端短路返回"未找到相关内容"话术(无参数校验依赖,校验后补)。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/11/4 00:37
 */
@Data
public class RagQueryRequest {

    /** 用户问题,原样作为 prompt 的 user 消息 */
    private String question;

    /** 要检索的知识库 ID 列表(可跨库);⚠️ 权限校验待接入 */
    private List<Long> kbIds;
}
