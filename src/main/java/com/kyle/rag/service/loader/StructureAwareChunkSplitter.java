package com.kyle.rag.service.loader;

import com.kyle.rag.service.splitter.ChunkConfig;
import com.kyle.rag.service.splitter.ChunkResult;
import com.kyle.rag.service.splitter.ChunkSplitter;
import com.kyle.rag.service.splitter.SlidingWindowChunkSplitter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 结构感知分块：优先沿用解析器的章节信息，没有章节信息时才按标题行切节。
 * 每个 PageContent 独立处理，避免跨页拼接后单个 pageNum 无法准确表示来源。
 *
 * 节大小不超过 chunkSize 时保留整节，超长节交给滑动窗口继续拆分。
 * 标题行保留在正文中，短节也保留；不跨节添加重叠，重叠只发生在同一长节内部。
 * 正文切分、字符数上限及 Token 粗估统一复用 SlidingWindowChunkSplitter。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/10/23 00:43
 */
@Component("structureAwareSplitter")
@Slf4j
public class StructureAwareChunkSplitter implements ChunkSplitter {

    /** 仅用于正文标题的启发式识别，不限制解析器提供的 sectionTitle 长度。 */
    private static final int MAX_HEADING_LENGTH = 80;

    // 识别 Markdown 1~6 级标题、中文章节、中文编号和带空格的多级数字标题。
    // 普通的 "1. 操作步骤"、"2026年发布" 仍视为正文，避免把列表和日期误判为章节。
    private static final Pattern HEADING_PATTERN = Pattern.compile(
            "^(?:#{1,6}\\s+\\S.*|第[零〇一二三四五六七八九十百千\\d]+[章节].*"
                    + "|[零〇一二三四五六七八九十百千]+、\\s*\\S.*|\\d+(?:\\.\\d+)+\\s+\\S.*)$"
    );

    private final SlidingWindowChunkSplitter slidingSplitter;

    public StructureAwareChunkSplitter(SlidingWindowChunkSplitter slidingSplitter) {
        this.slidingSplitter = slidingSplitter;
    }

    /**
     * 按来源顺序切节并分块，所有结果的 chunkIndex 在整份文档中从 0 连续递增。
     * 空结果、解析失败或无有效正文时返回空列表；配置始终先校验。
     *
     * @throws IllegalArgumentException 配置为空、块大小非正数，或重叠长度不在 [0, chunkSize) 内
     */
    @Override
    public List<ChunkResult> split(ParseResult parseResult, ChunkConfig config) {
        validateConfig(config);
        if (parseResult == null || !parseResult.isSuccess() || parseResult.getPages() == null) {
            return List.of();
        }

        List<TextSection> sections = extractSections(parseResult);
        List<ChunkResult> chunks = new ArrayList<>();

        for (TextSection section : sections) {
            ParseResult sectionResult = ParseResult.builder()
                    .success(true)
                    .pages(List.of(ParseResult.PageContent.builder()
                            .pageNum(section.pageNum())
                            .text(section.text())
                            .sectionTitle(section.title())
                            .build()))
                    .totalPages(1)
                    .build();

            // 短节自然得到一块，长节使用窗口和重叠；两种情况共享边界处理及 Token 估算。
            for (ChunkResult sub : slidingSplitter.split(sectionResult, config)) {
                sub.setChunkIndex(chunks.size());
                chunks.add(sub);
            }
        }

        log.debug("[结构分块] 完成分块，共{}节、{}块", sections.size(), chunks.size());
        return chunks;
    }

    /** 与滑动窗口采用相同约束，避免只有长节才触发配置校验。 */
    private void validateConfig(ChunkConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("分块参数不能为空");
        }
        if (config.getChunkSize() <= 0) {
            throw new IllegalArgumentException("chunkSize 必须大于 0");
        }
        if (config.getChunkOverlap() < 0 || config.getChunkOverlap() >= config.getChunkSize()) {
            throw new IllegalArgumentException("chunkOverlap 必须满足 0 <= chunkOverlap < chunkSize");
        }
    }

    /**
     * 保留解析器已有的分组和标题，无法还原上游已经合并的章节。
     * PDF 的 pageNum 是物理页码，DOCX/MD 则是解析器给出的节编号。
     */
    private List<TextSection> extractSections(ParseResult parseResult) {
        List<TextSection> sections = new ArrayList<>();
        for (ParseResult.PageContent page : parseResult.getPages()) {
            if (page == null || page.getText() == null || page.getText().isBlank()) {
                continue;
            }
            if (page.getSectionTitle() != null && !page.getSectionTitle().isBlank()) {
                sections.add(new TextSection(page.getSectionTitle(), page.getText().strip(), page.getPageNum()));
            } else {
                extractPageSections(page, sections);
            }
        }
        return sections;
    }

    /** 仅在当前页内识别标题，不把上一页的标题或正文带到下一页。 */
    private void extractPageSections(ParseResult.PageContent page, List<TextSection> sections) {
        StringBuilder current = new StringBuilder();
        String currentTitle = null;

        for (String line : page.getText().split("\\R", -1)) {
            String stripped = line.strip();
            if (stripped.length() <= MAX_HEADING_LENGTH && HEADING_PATTERN.matcher(stripped).matches()) {
                // 不设最小长度：前言、短章节和只有标题的节都应保留，避免正文丢失或标题错配。
                addSection(sections, currentTitle, current, page.getPageNum());
                current.setLength(0);
                currentTitle = stripped;
            }
            // 标题既是章节元数据，也是正文的一部分；其余行的内部空白与缩进照常保留。
            current.append(line).append('\n');
        }
        addSection(sections, currentTitle, current, page.getPageNum());
    }

    /** 只忽略纯空白，不因内容短而删除或合并章节。 */
    private void addSection(List<TextSection> sections, String title, StringBuilder content, int pageNum) {
        String text = content.toString().strip();
        if (!text.isBlank()) {
            sections.add(new TextSection(title, text, pageNum));
        }
    }

    /** 内部中间结果：每节正文只对应一个来源编号。 */
    private record TextSection(String title, String text, int pageNum) {}
}
