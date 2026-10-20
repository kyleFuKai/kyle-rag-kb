package com.kyle.rag.service.loader;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
/**
 * PDF 解析器，基于 PDFBox 3.x 逐页提取文本。
 * 每页单独提取以保留真实页码；整本提取不到任何文本（如纯图片扫描件）时返回 failure，
 * OCR 兜底不在本期范围。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/10/20 23:06
 */
@Component
@Slf4j
public class PdfParser implements DocumentParser{

    // 识别章节标题行：三选一的编号前缀 + 后接正文（\s*.+ 要求编号后还有内容，光秃秃的"第三章"不算）
    //   ① 第[一二三四五六七八九十百\d]+[章节] → 第三章 / 第12节
    //   ② [一二三四五六七八九十]+、          → 一、 / 十二、
    //   ③ \d+\.                              → 1. / 42.
    // 启发式识别，允许少量误伤（"2026.年度计划"会命中③）和漏检（编号独占一行、含"零"的不匹配）
    private static final Pattern HEADING_PATTERN =
            Pattern.compile("^(第[一二三四五六七八九十百\\d]+[章节]|[一二三四五六七八九十]+、|\\d+\\.)\\s*.+");

    @Override
    public String supportedType() {
        return "PDF";
    }

    /**
     * 加载 PDF → 逐页提取并清理文本 → 识别各页章节标题与文档标题。
     * 遵守接口契约：异常不外抛，单页失败跳过、不影响其他页。
     */
    @Override
    public ParseResult parse(InputStream inputStream, String fileName) {
        // PDFBox 3.x 弃用了 PDDocument.load(InputStream)，改用 Loader.loadPDF()；
        // 传字节数组意味着整份文件读进内存——受上传 50MB 上限约束，可接受
        try (PDDocument document = Loader.loadPDF(inputStream.readAllBytes())) {
            int totalPages = document.getNumberOfPages();
            List<ParseResult.PageContent> pages = new ArrayList<>();

            PDFTextStripper stripper = new PDFTextStripper();
            // PDF 里只有按坐标摆好的字形、没有"阅读顺序"：默认按写入顺序输出，多栏/文本框会串行；
            // 开启后按坐标重排（同行按 X、行间按 Y），还原眼睛看到的顺序
            stripper.setSortByPosition(true);

            // 逐页单独 setStartPage/EndPage 而不是整本一次 getText：页面文本要和真实页码一一对应
            for (int pageNum = 1; pageNum <= totalPages; pageNum++) {
                try {
                    stripper.setStartPage(pageNum);
                    stripper.setEndPage(pageNum);
                    String text = stripper.getText(document);
                    text = cleanText(text);

                    if (text.isBlank()) {
                        log.debug("[PDF解析] 第{}页内容为空，可能是图片页，跳过", pageNum);
                        continue;
                    }

                    pages.add(ParseResult.PageContent.builder()
                            .pageNum(pageNum)
                            .text(text)
                            .sectionTitle(detectHeading(text))
                            .build());

                } catch (Exception e) {
                    // 单页解析失败不影响其他页
                    log.warn("[PDF解析] 第{}页解析失败：{}", pageNum, e.getMessage());
                }
            }

            if (pages.isEmpty()) {
                return ParseResult.failure("PDF 解析后无有效文本内容，可能是纯图片 PDF，需要 OCR 处理");
            }

            log.info("[PDF解析] 文件={}，总页数={}，有效页数={}", fileName, totalPages, pages.size());

            return ParseResult.builder()
                    .success(true)
                    .pages(pages)
                    .totalPages(totalPages)
                    .title(extractTitle(pages))
                    .build();

        } catch (Exception e) {
            log.error("[PDF解析] 文件={}，解析失败：{}", fileName, e.getMessage(), e);
            return ParseResult.failure("PDF 解析失败：" + e.getMessage());
        }
    }

    /** 清理 PDF 解析出的文本：提取器的空格/换行是按坐标推测出来的，必然带毛边，这里统一规整 */
    private String cleanText(String raw) {
        if (raw == null) return "";
        return raw
                .replaceAll("\\r\\n", "\n")
                .replaceAll("\\r", "\n")
                .replaceAll("[ \\t]+", " ")          // 多个空格合并
                .replaceAll("\\n{3,}", "\n\n")        // 多个空行合并为两个
                .strip();
    }

    /** 从文本开头前 3 行里找章节标题；行长限制 2~50 是为了跳过正文长句和残行 */
    private String detectHeading(String text) {
        String[] lines = text.split("\n");
        for (int i = 0; i < Math.min(3, lines.length); i++) {
            String line = lines[i].strip();
            if (line.length() > 2 && line.length() < 50) {
                Matcher m = HEADING_PATTERN.matcher(line);
                if (m.matches()) return line;
            }
        }
        return null;
    }

    /** 取第一页文本的第一行作为文档标题 */
    private String extractTitle(List<ParseResult.PageContent> pages) {
        if (pages.isEmpty()) return null;
        String firstPageText = pages.get(0).getText();
        String[] lines = firstPageText.split("\n");
        for (String line : lines) {
            line = line.strip();
            if (!line.isBlank() && line.length() < 100) {
                return line;
            }
        }
        return null;
    }
}
