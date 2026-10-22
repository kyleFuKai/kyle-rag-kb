package com.kyle.rag.service.splitter;

import lombok.Builder;
import lombok.Data;
/**
 * 分块阶段的单块输出，包含正文、文档内顺序、来源位置及 Token 估算值。
 * 下游可据此生成向量、组装入库实体，并在检索结果中展示引用来源。
 * 文档 ID、知识库 ID 和版本号等业务信息由调用层在组装入库数据时补充。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/10/22 19:47
 */
@Data
@Builder
public class ChunkResult {

    /**
     * 当前块在整份文档中的顺序索引，从 0 开始。
     * 跨页或跨章节继续递增，供入库及检索后恢复原文顺序时使用。
     */
    private int chunkIndex;

    /**
     * 当前块的正文，供后续向量化、检索和拼接 Prompt 使用。
     * 如果策略设置了重叠，此处包含与相邻块重复的那部分正文。
     */
    private String content;

    /**
     * 沿用解析阶段的来源位置编号，从 1 开始：PDF 为物理页码，DOCX/MD 为节序号，TXT 为 1。
     * 展示引用或映射到入库实体时，应根据文档类型解释该值，避免把节序号当成物理页码。
     */
    private Integer pageNum;

    /**
     * 解析阶段识别出的章节标题，未识别时可为 null。
     * 用于保留章节归属，以及后续引用展示和补充上下文。
     */
    private String sectionTitle;

    /**
     * 当前块正文的 Token 估算数，用于检索资料的上下文预算。
     * 数值取决于估算方法及目标模型的分词规则，不保证等于模型实际计数。
     * 系统提示、历史消息和引用前缀等额外内容，需要在组装 Prompt 时另行计入。
     */
    private int estimatedTokens;
}
