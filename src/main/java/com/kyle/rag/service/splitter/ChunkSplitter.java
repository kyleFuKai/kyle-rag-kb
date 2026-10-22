package com.kyle.rag.service.splitter;

import com.kyle.rag.service.loader.ParseResult;

import java.util.List;
/**
 * 文档分块策略的统一接口，连接文档解析阶段与后续向量化阶段。
 * 滑动窗口、结构感知等策略实现同一个方法，调用层可以根据文档结构和配置选择实现。
 * 输出包含文本及来源定位信息，向量化和持久化由下游流程负责。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/10/22 19:46
 */
public interface ChunkSplitter {

    /**
     * 按原文顺序将解析结果拆分为若干块，并保留来源页或章节信息。
     * 调用前应确认文档解析成功，且分块大小、重叠长度等参数合法。
     * 实现时应在整份文档范围内连续编号 chunkIndex，从 0 开始，翻页或换节时继续递增。
     *
     * @param parseResult 成功的文档解析结果；pages 中的一项可能是 PDF 物理页、DOCX/MD 章节或整篇 TXT
     * @param config      本次分块参数，长度单位为字符数
     * @return 按原文顺序排列的分块列表；无有效正文时应返回空列表
     */
    List<ChunkResult> split(ParseResult parseResult, ChunkConfig config);
}
