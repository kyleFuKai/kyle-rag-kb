package com.kyle.rag.service;

import com.kyle.rag.service.loader.ParseResult;
import com.kyle.rag.service.splitter.ChunkConfig;
import com.kyle.rag.service.splitter.ChunkResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 分块服务集成测试：验证 Spring 配置注入、策略选择、正文完整性及来源信息。
 * 测试窗口固定为 64/16，避免 application.yml 的业务参数变化影响预期结果。
 * 通过 main 初始化项目的 dotenv 配置，因此运行时仍需要项目配置及数据库环境。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/10/23 01:34
 */
@SpringBootTest(
        useMainMethod = SpringBootTest.UseMainMethod.ALWAYS,
        properties = {"rag.chunk.size=64", "rag.chunk.overlap=16"}
)
public class ChunkServiceTest {

    @Autowired
    private ChunkService chunkService;

    @Test
    @DisplayName("默认入口读取 Spring 配置，并生成正文准确、编号连续的块")
    void defaultConfigurationProducesExactWindowsAndPreservesSource() {
        String text = uniqueText(150);

        List<ChunkResult> chunks = chunkService.chunk(document(page(3, null, text)));

        // 无断点、无首尾空白时，64/16 窗口的起点依次为 0、48、96。
        // 比较完整正文及块数，能发现错位、缺块、多块和只返回首块等问题。
        assertThat(chunks).extracting(ChunkResult::getChunkIndex, ChunkResult::getPageNum,
                        ChunkResult::getSectionTitle, ChunkResult::getContent)
                .containsExactly(
                        tuple(0, 3, null, text.substring(0, 64)),
                        tuple(1, 3, null, text.substring(48, 112)),
                        tuple(2, 3, null, text.substring(96)));

        // 块数已由 containsExactly 保证，逐对验证完整重叠，不使用可能误通过的 contains。
        for (int i = 1; i < chunks.size(); i++) {
            String previous = chunks.get(i - 1).getContent();
            String current = chunks.get(i).getContent();
            assertThat(current.substring(0, 16))
                    .isEqualTo(previous.substring(previous.length() - 16));
        }
    }

    @Test
    @DisplayName("显式配置覆盖默认窗口参数")
    void explicitConfigurationOverridesDefaults() {
        String text = uniqueText(180);

        List<ChunkResult> chunks = chunkService.chunk(
                document(page(1, null, text)), config(80, 20, false));

        assertThat(chunks).extracting(ChunkResult::getContent)
                .containsExactly(text.substring(0, 80), text.substring(60, 140), text.substring(120));
    }

    @Test
    @DisplayName("零重叠时，按顺序拼接所有块可恢复完整正文")
    void zeroOverlapPreservesAllContent() {
        String text = uniqueText(150);

        List<ChunkResult> chunks = chunkService.chunk(
                document(page(1, null, text)), config(64, 0, false));

        assertThat(chunks).extracting(ChunkResult::getContent)
                .containsExactly(text.substring(0, 64), text.substring(64, 128), text.substring(128));
        assertThat(chunks.stream().map(ChunkResult::getContent).collect(Collectors.joining()))
                .isEqualTo(text);
    }

    @Test
    @DisplayName("回退到句子边界后，后续窗口仍正确推进并保留文末")
    void sentenceBoundaryChangesTheWindowWithoutLosingContent() {
        String body = uniqueText(210);
        String text = body.substring(0, 90) + "。" + body.substring(90);

        List<ChunkResult> chunks = chunkService.chunk(
                document(page(1, null, text)), config(128, 16, false));

        // 第一块在句号之后结束于 91，下一块从 91 - 16 = 75 开始。
        assertThat(chunks).extracting(ChunkResult::getContent)
                .containsExactly(text.substring(0, 91), text.substring(75, 203), text.substring(187));
        String restored = chunks.getFirst().getContent() + chunks.stream().skip(1)
                .map(chunk -> chunk.getContent().substring(16))
                .collect(Collectors.joining());
        assertThat(restored).isEqualTo(text);
    }

    @Test
    @DisplayName("小于窗口的正文只生成一个块")
    void textSmallerThanWindowProducesOneCompleteChunk() {
        // 当前 Service 会过滤不足 20 字符的块；此用例覆盖 40 字符正文的单块行为，
        // 不把“至少 20 字符”当作所有正确分块都必须满足的约束。
        String text = uniqueText(40);

        List<ChunkResult> chunks = chunkService.chunk(document(page(7, null, text)));

        assertThat(chunks).extracting(ChunkResult::getChunkIndex, ChunkResult::getPageNum,
                        ChunkResult::getContent)
                .containsExactly(tuple(0, 7, text));
    }

    @ParameterizedTest(name = "结构感知开关={0}")
    @ValueSource(booleans = {true, false})
    @DisplayName("结构感知开关决定是否继续识别无标题页面中的章节")
    void structureAwareFlagSelectsTheExpectedStrategy(boolean structureAware) {
        String anchorText = uniqueText(40);
        String body = uniqueText(80);
        String installation = "# 安装说明\n" + body.substring(0, 40);
        String removal = "# 卸载说明\n" + body.substring(40);
        String text = installation + "\n" + removal;

        // 当前 Service 至少需要一条上游标题才会考虑结构策略，因此保留一个有标题的来源。
        // 第二条正文小于 128 字符：结构策略得到两节，滑动窗口则保留为一块，能区分两个分支。
        ParseResult input = document(page(3, "上游章节", anchorText), page(5, null, text));
        List<ChunkResult> chunks = chunkService.chunk(input, config(128, 16, structureAware));

        if (structureAware) {
            assertThat(chunks).extracting(ChunkResult::getChunkIndex, ChunkResult::getPageNum,
                            ChunkResult::getSectionTitle, ChunkResult::getContent)
                    .containsExactly(
                            tuple(0, 3, "上游章节", anchorText),
                            tuple(1, 5, "# 安装说明", installation),
                            tuple(2, 5, "# 卸载说明", removal));
        } else {
            assertThat(chunks).extracting(ChunkResult::getChunkIndex, ChunkResult::getPageNum,
                            ChunkResult::getSectionTitle, ChunkResult::getContent)
                    .containsExactly(
                            tuple(0, 3, "上游章节", anchorText),
                            tuple(1, 5, null, text));
        }
    }

    @ParameterizedTest
    @MethodSource("emptyInputs")
    @DisplayName("没有有效正文时返回空列表")
    void emptyOrFailedInputProducesNoChunks(ParseResult input) {
        assertThat(chunkService.chunk(input)).isEmpty();
    }

    private static Stream<ParseResult> emptyInputs() {
        return Stream.of(
                (ParseResult) null,
                ParseResult.failure("解析失败"),
                document(),
                document(page(1, null, " \n\t")),
                document(page(1, null, null)));
    }

    @ParameterizedTest
    @CsvSource({"0,0", "-1,0", "64,-1", "64,64", "64,65"})
    @DisplayName("两种策略均拒绝非法窗口参数")
    void invalidWindowParametersAreRejected(int chunkSize, int overlap) {
        String text = uniqueText(40);

        for (boolean structureAware : new boolean[]{true, false}) {
            assertThatThrownBy(() -> chunkService.chunk(document(page(1, "章节", text)),
                    config(chunkSize, overlap, structureAware)))
                    .as("structureAware=%s", structureAware)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    /** 每个字符不同，且不含空白和标点，便于精确比较窗口位置及重叠内容。 */
    private static String uniqueText(int length) {
        return IntStream.range(0, length)
                .mapToObj(i -> String.valueOf((char) ('\u4e00' + i)))
                .collect(Collectors.joining());
    }

    private static ChunkConfig config(int chunkSize, int overlap, boolean structureAware) {
        return ChunkConfig.builder().chunkSize(chunkSize).chunkOverlap(overlap)
                .structureAware(structureAware).build();
    }

    private static ParseResult.PageContent page(int pageNum, String title, String text) {
        return ParseResult.PageContent.builder().pageNum(pageNum).sectionTitle(title).text(text).build();
    }

    private static ParseResult document(ParseResult.PageContent... pages) {
        return ParseResult.builder().success(true).pages(List.of(pages)).totalPages(pages.length).build();
    }
}
