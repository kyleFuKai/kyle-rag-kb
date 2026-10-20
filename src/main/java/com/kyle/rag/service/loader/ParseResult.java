package com.kyle.rag.service.loader;

import lombok.Builder;
import lombok.Data;

import java.util.List;
/**
 * 文档解析的统一出参：解析器（PDF / DOCX / TXT...）读完原始文件后产出，
 * 成功时 pages 带按页文本，失败时 success=false + errorMsg，由上层更新文档状态。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/10/20 21:45
 */
@Data
@Builder
public class ParseResult {

    /** 解析是否成功 */
    private boolean success;

    /** 错误信息（success=false 时有值） */
    private String errorMsg;

    /** 解析出的页面列表（PDF 按页，其他格式整体算一页） */
    private List<PageContent> pages;

    /** 文档总页数 */
    private int totalPages;

    /** 文档标题（如果能识别） */
    private String title;

    /** 单页解析内容：PDF 一页一条（pageNum=物理页码）；DOCX 一条=一节（pageNum=节序号）；TXT/MD 整体一条 */
    @Data
    @Builder
    public static class PageContent {
        /** 页码（1-based） */
        private int pageNum;
        /** 该页的纯文本内容 */
        private String text;
        /** 该页识别到的章节标题（可能为空） */
        private String sectionTitle;
    }

    public static ParseResult failure(String errorMsg) {
        return ParseResult.builder()
                .success(false)
                .errorMsg(errorMsg)
                .pages(List.of())
                .build();
    }

    /** 获取所有页的合并文本 */
    public String getFullText() {
        if (pages == null) return "";
        return pages.stream()
                .map(PageContent::getText)
                .filter(t -> t != null && !t.isBlank())
                .reduce("", (a, b) -> a + "\n\n" + b)
                .strip();
    }
}
