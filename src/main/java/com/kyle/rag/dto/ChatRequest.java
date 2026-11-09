package com.kyle.rag.dto;

import lombok.Data;
import java.util.List;
/**
 * 对话问答的请求体:sessionId 为空表示新开一个会话(服务端生成并返回)。
 */
@Data
public class ChatRequest {

    /** 用户问题 */
    private String question;
    /** 要检索的知识库 ID 列表 */
    private List<Long> kbIds;
    /** 会话 ID;为空 = 新会话(首轮),非空 = 在既有会话中继续多轮 */
    private String sessionId;
}
