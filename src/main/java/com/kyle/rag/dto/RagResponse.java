package com.kyle.rag.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
/**
 * RAG 问答的结构化响应:答案 + 引用来源 + 耗时。
 * sources 按回答中引用出现的顺序排列(模型未标注引用时为全部候选),前端可用于"查看引用原文"交互;
 * notFound=true 表示检索/置信度过滤后无有效内容,answer 为引导话术,此时 sources 为空。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/11/5 23:54
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagResponse {

    /** 模型生成的回答;notFound 时为引导话术 */
    private String answer;

    /** 引用来源,按精排相关性降序;notFound 时为空列表 */
    private List<Source> sources;

    /** 管道端到端耗时(检索+精排+生成),毫秒 */
    private int latencyMs;

    /** 是否未命中(检索空或全被置信度过滤);true 时 answer 是引导话术而非真实回答 */
    private boolean notFound;

    /** 单条引用来源:chunk 定位信息 + 摘要 + 相关性分数 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Source {
        /** 分块 ID,回查原文用 */
        private Long chunkId;
        /** 所属文档 ID,前端跳转文档用 */
        private Long docId;

        /** 文档文件名,展示用 */
        private String docName;
        /** 页码/节序号(语义随 fileType,PDF=物理页) */
        private Integer pageNum;
        /** 章节标题 */
        private String sectionTitle;
        /** 相关段落摘要(前 200 字) */
        private String excerpt;
        /** 相关性分数:经 Reranker 时为精排分 [0,1],未精排时为 RRF 排名制分数 */
        private double score;
    }

    /** 工厂:检索/过滤后无有效内容时的标准响应 */
    public static RagResponse notFound() {
        return RagResponse.builder()
                .answer("在知识库中未找到与该问题相关的内容。建议您：\n" +
                        "1. 确认问题是否属于该知识库的覆盖范围\n" +
                        "2. 尝试更换关键词提问\n" +
                        "3. 联系相关部门获取准确信息")
                .sources(List.of())
                .notFound(true)
                .build();
    }
}
