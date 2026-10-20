package com.kyle.rag.service.loader;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
/**
 * Markdown 解析器：按 ATX 标题（# 一级 / ## 二级）切节，pageNum=节序号（语义同 DOCX）。
 * 代码块（``` 围栏）内的 # 不当标题；切好的节经 stripMarkdownSyntax 去掉语法符号，
 * 下游检索/拼 prompt 用纯文本。文档完全没有标题时整体算一节。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/10/20 23:57
 */
@Component
@Slf4j
public class MarkdownParser implements DocumentParser{

    // 匹配 1~3 级 ATX 标题；实际切节只认 # / ##（见 parse 里的条件），### 留在节内
    private static final Pattern HEADING_PATTERN = Pattern.compile("^#{1,3}\\s+(.+)");

    @Override
    public String supportedType() {
        return "MD";
    }

    @Override
    public ParseResult parse(InputStream inputStream, String fileName) {
        try {
            String markdown = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            List<ParseResult.PageContent> pages = new ArrayList<>();

            // 按一级/二级标题切分为多个节
            String[] lines = markdown.split("\n");
            StringBuilder currentSection = new StringBuilder();
            String currentTitle = null;
            int sectionCount = 0;
            boolean inCodeBlock = false;  // 跟踪是否在 ``` 代码块内，避免代码块里的 # 注释被误识别为标题

            for (String line : lines) {
                // 代码块边界（``` 或 ```语言）——只切换状态，不参与标题判断
                if (line.startsWith("```")) {
                    inCodeBlock = !inCodeBlock;
                    currentSection.append(line).append("\n");
                    continue;
                }
                // 代码块内部：原样保留，不识别标题
                if (inCodeBlock) {
                    currentSection.append(line).append("\n");
                    continue;
                }

                Matcher m = HEADING_PATTERN.matcher(line);
                // 只按一二级标题切节，三级留在节内——再细就把节切碎了
                if (m.matches() && (line.startsWith("# ") || line.startsWith("## "))) {
                    // 当前节内容够多（>100 字符）才切，短节并入后续内容（与 DOCX 的 200 同思路，MD 节普遍更小）
                    if (currentSection.length() > 100) {
                        pages.add(ParseResult.PageContent.builder()
                                .pageNum(++sectionCount)
                                .text(stripMarkdownSyntax(currentSection.toString()))
                                .sectionTitle(currentTitle)
                                .build());
                        currentSection = new StringBuilder();
                    }
                    currentTitle = m.group(1);
                }
                currentSection.append(line).append("\n");
            }

            if (!currentSection.isEmpty()) {
                pages.add(ParseResult.PageContent.builder()
                        .pageNum(++sectionCount)
                        .text(stripMarkdownSyntax(currentSection.toString()))
                        .sectionTitle(currentTitle)
                        .build());
            }

            // 如果切分后没有内容（文档没有标题），整体作为一页
            if (pages.isEmpty()) {
                pages.add(ParseResult.PageContent.builder()
                        .pageNum(1)
                        .text(stripMarkdownSyntax(markdown))
                        .build());
            }

            log.info("[MD解析] 文件={}，分节={}节", fileName, pages.size());

            return ParseResult.builder()
                    .success(true)
                    .pages(pages)
                    .totalPages(pages.size())
                    .build();

        } catch (Exception e) {
            log.error("[MD解析] 文件={}，解析失败：{}", fileName, e.getMessage(), e);
            return ParseResult.failure("Markdown 解析失败：" + e.getMessage());
        }
    }

    /** 去除 Markdown 语法符号，提取纯文本（列表/标题正则用 (?m) 多行模式，逐行匹配） */
    private String stripMarkdownSyntax(String markdown) {
        return markdown
                .replaceAll("```[\\s\\S]*?```", " [代码块] ")   // 代码块整体换成标记：必须最先做，防止块内 # * 等被后面规则误伤
                .replaceAll("`([^`]+)`", "$1")                   // 行内代码去掉反引号
                .replaceAll("!\\[.*?\\]\\(.*?\\)", " [图片] ")   // 图片必须在链接之前：否则链接规则先吃掉 []() 只剩个 !
                .replaceAll("\\[([^\\]]+)\\]\\(.*?\\)", "$1")    // 链接保留文字、去掉地址
                .replaceAll("(?m)^#{1,6}\\s+", "")               // 标题符号（多行模式：逐行）
                .replaceAll("\\*\\*([^*]+)\\*\\*", "$1")         // 加粗
                .replaceAll("\\*([^*]+)\\*", "$1")               // 斜体
                .replaceAll("(?m)^[-*+]\\s+", "")                // 无序列表（多行模式：逐行）
                .replaceAll("(?m)^\\d+\\.\\s+", "")              // 有序列表（多行模式：逐行）
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }
}
