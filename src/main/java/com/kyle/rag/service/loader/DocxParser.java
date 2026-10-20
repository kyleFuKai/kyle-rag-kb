package com.kyle.rag.service.loader;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
/**
 * DOCX 解析器（POI XWPF）。DOCX 没有"页"的概念，这里按标题样式（Heading / 标题）把文档切成节，
 * 每个 PageContent 是一节：pageNum 存的是节序号（非物理页码），sectionTitle 为节标题。
 * 表格不单独成节，按原文位置并入所在节（单元格用 | 拼接，外面包 [表格] 标记）。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/10/20 23:26
 */
@Component
@Slf4j
public class DocxParser implements DocumentParser{

    @Override
    public String supportedType() {
        return "DOCX";
    }

    /**
     * 按文档顺序遍历段落与表格，遇到新标题就切节（当前节攒够一定长度才切，避免碎片节）。
     * 遵守接口契约：异常不外抛。
     */
    @Override
    public ParseResult parse(InputStream inputStream, String fileName) {
        try (XWPFDocument document = new XWPFDocument(inputStream)) {
            List<ParseResult.PageContent> pages = new ArrayList<>();
            StringBuilder currentSection = new StringBuilder();
            String currentTitle = null;
            int sectionCount = 0;

            // 按文档实际顺序遍历段落和表格，保证表格归属到正确的节
            for (IBodyElement elem : document.getBodyElements()) {
                if (elem instanceof XWPFParagraph paragraph) {
                    String text = paragraph.getText();
                    if (text == null || text.isBlank()) continue;

                    // 识别标题样式：英文 Word 是 Heading 1/2/3，中文版样式名叫"标题 1/2/3"
                    String style = paragraph.getStyle();
                    boolean isHeading = style != null &&
                            (style.startsWith("Heading") || style.startsWith("heading") ||
                                    style.contains("标题"));

                    if (isHeading && currentSection.length() > 200) {
                        // 新标题且当前节内容够多（>200 字符）：保存当前节、另起新节
                        pages.add(ParseResult.PageContent.builder()
                                .pageNum(++sectionCount)
                                .text(currentSection.toString().strip())
                                .sectionTitle(currentTitle)
                                .build());
                        currentSection = new StringBuilder();
                        currentTitle = text;
                    } else if (isHeading) {
                        // 当前节内容太少，不切，只把标题换成新标题——短节并入后续内容
                        currentTitle = text;
                    }

                    currentSection.append(text).append("\n");

                } else if (elem instanceof XWPFTable table) {
                    // 表格 append 到当前节，按文档原始位置归属
                    StringBuilder tableText = new StringBuilder();
                    for (XWPFTableRow row : table.getRows()) {
                        List<String> cellTexts = row.getTableCells().stream()
                                .map(XWPFTableCell::getText)
                                .filter(t -> !t.isBlank())
                                .toList();
                        if (!cellTexts.isEmpty()) {
                            tableText.append(String.join(" | ", cellTexts)).append("\n");
                        }
                    }
                    if (!tableText.isEmpty()) {
                        // [表格] 标记 + 单元格 | 拼接：给下游留表格线索，分块/引用展示时能认出这是表格而非正文
                        currentSection.append("\n[表格]\n").append(tableText);
                    }
                }
            }

            // 保存最后一节
            if (!currentSection.isEmpty()) {
                pages.add(ParseResult.PageContent.builder()
                        .pageNum(++sectionCount)
                        .text(currentSection.toString().strip())
                        .sectionTitle(currentTitle)
                        .build());
            }

            if (pages.isEmpty()) {
                return ParseResult.failure("Word 文档内容为空");
            }

            // 提取文档属性中的标题（用 try-with-resources 释放资源）
            String title = null;
            try (XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
                title = extractor.getCoreProperties().getTitle();
            } catch (Exception ignored) {}   // 文档属性里没填标题很常见，拿不到就不填，不影响主流程

            log.info("[DOCX解析] 文件={}，段落分节={}节", fileName, pages.size());

            return ParseResult.builder()
                    .success(true)
                    .pages(pages)
                    .totalPages(pages.size())
                    .title(title)
                    .build();

        } catch (Exception e) {
            log.error("[DOCX解析] 文件={}，解析失败：{}", fileName, e.getMessage(), e);
            return ParseResult.failure("Word 文档解析失败：" + e.getMessage());
        }
    }
}
