package com.kyle.rag.service.splitter;

import com.kyle.rag.service.loader.ParseResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 按页或节独立执行滑动窗口分块，保留解析阶段的来源编号和章节标题。
 * chunkSize 是正文的字符数上限；窗口未到文本末尾时，尝试回退到段落或句子边界。
 * 下一窗口通常从实际结束位置减去 chunkOverlap 开始，因此边界回退后步长可能变化。
 *
 * 每个块只来自一个 PageContent，避免跨页或跨节合并后，单个 pageNum 无法表达完整来源范围。
 * 空白正文会跳过，chunkIndex 在整份文档中从 0 连续递增。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/10/23 00:23
 */
@Component
@Slf4j
public class SlidingWindowChunkSplitter implements ChunkSplitter {

    /** 从预计结束位置最多向前查找的字符数，实际查找范围还受当前窗口起点限制。 */
    private static final int MAX_BACKTRACK = 100;

    /** 按优先级依次寻找断点：段落、换行、中文句末标点、分号、逗号、空格。 */
    private static final String[] BREAK_CHARS = {"\n\n", "\n", "。", "！", "？", "；", "，", " "};

    /**
     * 校验参数后，逐页或逐节分块；每个结果继承其来源的 pageNum 和 sectionTitle。
     * 无解析结果、解析失败或页面列表缺失时返回空列表。
     *
     * @throws IllegalArgumentException 配置为空、块大小非正数，或重叠长度不在 [0, chunkSize) 内
     */
    @Override
    public List<ChunkResult> split(ParseResult parseResult, ChunkConfig config) {
        validateConfig(config);
        if (parseResult == null || !parseResult.isSuccess() || parseResult.getPages() == null) {
            return List.of();
        }

        int chunkSize = config.getChunkSize();
        int overlap = config.getChunkOverlap();
        List<ChunkResult> chunks = new ArrayList<>();
        // 编号放在页面循环外，保证换页或换节时不会重新从 0 开始。
        int chunkIndex = 0;

        for (ParseResult.PageContent page : parseResult.getPages()) {
            String text = page.getText();
            if (text == null || text.isBlank()) continue;

            List<String> pageChunks = splitText(text, chunkSize, overlap);

            for (String chunkText : pageChunks) {
                if (chunkText.isBlank()) continue;

                chunks.add(ChunkResult.builder()
                        .chunkIndex(chunkIndex++)
                        .content(chunkText)
                        .pageNum(page.getPageNum())
                        .sectionTitle(page.getSectionTitle())
                        .estimatedTokens(estimateTokens(chunkText))
                        .build());
            }
        }

        log.debug("[分块] 文档分块完成，共{}块，avgSize={}字符",
                chunks.size(),
                chunks.isEmpty() ? 0 : chunks.stream()
                        .mapToInt(c -> c.getContent().length()).average().orElse(0));

        return chunks;
    }

    /**
     * 在入口拒绝无效参数：零长度窗口无法前进，负重叠会跳过正文，过大的重叠会阻碍窗口推进。
     * ChunkConfig 本身只保存参数，因此这里负责落实其取值约束。
     */
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
     * 对单页或单节正文分块，位置区间使用 substring 的左闭右开约定：[start, end)。
     * 参数在入口已校验；断点查找保证 end > start，后续推进保证下一次 start 严格增大。
     */
    private List<String> splitText(String text, int chunkSize, int overlap) {
        List<String> result = new ArrayList<>();
        int start = 0;

        while (start < text.length()) {
            int end = start + Math.min(chunkSize, text.length() - start);

            // 如果没有到文本末尾，尝试在句子/段落边界处断开
            if (end < text.length()) {
                end = findGoodBreakPoint(text, start, end);
            }

            // 只清理每块首尾空白，正文内部的换行和缩进保持原样。
            String chunk = text.substring(start, end).strip();
            if (!chunk.isBlank()) {
                result.add(chunk);
            }

            // 到达本页/本节末尾后停止，避免再输出一个只包含重叠尾部的块。
            if (end >= text.length()) break;

            int nextStart = end - overlap;
            if (nextStart <= start) {
                // 回退后块长可能不超过 overlap；此时放弃本次重叠，推进到 end，确保不会停滞。
                nextStart = end;
            }
            start = nextStart;
        }

        return result;
    }

    /**
     * 从预计结束位置向前寻找优先级较高的断点，返回值是分隔符之后的结束下标。
     * 分隔符必须完整位于当前窗口内，最多回退 MAX_BACKTRACK 字符；找不到时使用原结束位置。
     * 将 start 纳入查找边界，保证 {@code start < end && end <= position}，避免找到上一块的标点后无法前进。
     */
    private int findGoodBreakPoint(String text, int start, int position) {
        int searchLimit = Math.max(start, position - MAX_BACKTRACK);

        for (String breakChar : BREAK_CHARS) {
            // 从 position - 分隔符长度 起向前找：保证 idx + 分隔符长度 <= position，
            // 且只扫描当前窗口末端的有限区间，避免在长文档上反复搜索整个前缀。
            for (int idx = position - breakChar.length(); idx >= searchLimit; idx--) {
                if (text.startsWith(breakChar, idx)) {
                    return idx + breakChar.length();
                }
            }
        }

        return position;  // 找不到好的断点，直接截断
    }

    /**
     * 按经验系数粗估正文 Token：基本汉字每字按 1.5，其余非空白字符每个按 0.3。
     * 空白不计入本次估算；标点、其他文字和特殊符号均归入后一类，可能存在明显误差。
     * 此值供预算参考，不保证是实际 Token 数的上界；Prompt 中额外加入的内容需另行统计。
     */
    private int estimateTokens(String text) {
        if (text == null) return 0;
        int chineseChars = 0;
        int otherChars = 0;
        for (char c : text.toCharArray()) {
            if (c >= '\u4e00' && c <= '\u9fff') {
                chineseChars++;
            } else if (!Character.isWhitespace(c)) {
                otherChars++;
            }
        }
        // 向上取整，避免 API 等短文本因直接截断小数而得到 0 Token。
        return (int) Math.ceil(chineseChars * 1.5 + otherChars * 0.3);
    }
}
