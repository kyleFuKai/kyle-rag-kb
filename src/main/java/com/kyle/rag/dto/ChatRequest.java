package com.kyle.rag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
/**
 * 对话问答的请求体:sessionId 为空表示新开一个会话(服务端生成并返回)。
 * question / kbIds 的约束与 RagQueryRequest 一致,空值在 Controller 层即 400。
 */
@Data
public class ChatRequest {

    /** 用户问题 */
    @NotBlank(message = "问题不能为空")
    @Size(max = 2000, message = "问题过长(最多 2000 字)")
    private String question;

    /** 要检索的知识库 ID 列表 */
    @NotEmpty(message = "知识库列表不能为空")
    private List<Long> kbIds;

    /** 会话 ID;为空 = 新会话(首轮),非空 = 在既有会话中继续多轮 */
    private String sessionId;
}
