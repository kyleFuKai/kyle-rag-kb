package com.kyle.rag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
/**
 * RAG 问答的请求体。question 与 kbIds 均有 Bean Validation 约束:
 * 问题为空会在 embedding 阶段抛异常、kbIds 为空会在权限过滤阶段报"无权访问",
 * 两者都在 Controller 层就以 400 拦截,避免变成 500。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/11/4 00:37
 */
@Data
public class RagQueryRequest {

    /** 用户问题,原样作为 prompt 的 user 消息 */
    @NotBlank(message = "问题不能为空")
    @Size(max = 2000, message = "问题过长(最多 2000 字)")
    private String question;

    /** 要检索的知识库 ID 列表(可跨库) */
    @NotEmpty(message = "知识库列表不能为空")
    private List<Long> kbIds;
}
