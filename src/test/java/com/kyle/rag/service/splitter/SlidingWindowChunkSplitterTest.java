package com.kyle.rag.service.splitter;

import com.kyle.rag.service.loader.ParseResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class SlidingWindowChunkSplitterTest {

    private final SlidingWindowChunkSplitter splitter = new SlidingWindowChunkSplitter();

    @Test
    void smallWindowDoesNotReturnToAPreviousBreakpoint() {
        String text = "a".repeat(10) + "。" + "b".repeat(200);

        List<ChunkResult> chunks = assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> splitter.split(document(page(1, null, text)), config(64, 16)));

        assertThat(chunks).hasSize(5).allSatisfy(chunk ->
                assertThat(chunk.getContent()).isNotBlank().hasSizeLessThanOrEqualTo(64));
        assertThat(chunks.getFirst().getContent()).isEqualTo("a".repeat(10) + "。");
        assertThat(chunks.getLast().getContent()).isEqualTo(text.substring(155));
    }

    @Test
    void zeroOverlapPreservesAllTextAfterAShortSentence() {
        String text = "a".repeat(10) + "。" + "b".repeat(200) + "正文结尾";

        List<ChunkResult> chunks = assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> splitter.split(document(page(1, null, text)), config(64, 0)));

        assertThat(chunks.stream().map(ChunkResult::getContent).collect(Collectors.joining()))
                .isEqualTo(text);
    }

    @Test
    void uninterruptedTextHasExactOverlapWithoutAnExtraTailChunk() {
        String text = IntStream.range(0, 150)
                .mapToObj(i -> String.valueOf((char) ('A' + i % 26)))
                .collect(Collectors.joining());

        List<ChunkResult> chunks = splitter.split(document(page(1, null, text)), config(64, 16));

        assertThat(chunks).extracting(ChunkResult::getContent)
                .containsExactly(text.substring(0, 64), text.substring(48, 112), text.substring(96));
    }

    @ParameterizedTest
    @CsvSource({"0,0", "-1,0", "64,-1", "64,64", "64,65"})
    void invalidWindowParametersAreRejectedBeforeReadingInput(int chunkSize, int overlap) {
        assertThrows(IllegalArgumentException.class,
                () -> splitter.split(null, config(chunkSize, overlap)));
    }

    @Test
    void nullConfigIsRejectedBeforeReadingInput() {
        assertThrows(IllegalArgumentException.class, () -> splitter.split(null, null));
    }

    @Test
    void pagesKeepTheirMetadataAndShareOneContinuousChunkIndex() {
        ParseResult input = document(
                page(3, "第一章", "A".repeat(70)),
                page(4, "空白页", " \n\t"),
                page(5, null, null),
                page(7, "第二章", "Z".repeat(70)));

        List<ChunkResult> chunks = splitter.split(input, config(64, 0));

        assertThat(chunks).extracting(ChunkResult::getChunkIndex, ChunkResult::getPageNum,
                        ChunkResult::getSectionTitle, ChunkResult::getContent)
                .containsExactly(
                        tuple(0, 3, "第一章", "A".repeat(64)),
                        tuple(1, 3, "第一章", "A".repeat(6)),
                        tuple(2, 7, "第二章", "Z".repeat(64)),
                        tuple(3, 7, "第二章", "Z".repeat(6)));
    }

    @ParameterizedTest
    @CsvSource({"API,1", "中,2", "中文,3", "中A,2"})
    void tokenEstimateRoundsUpEvenForShortText(String text, int expectedTokens) {
        List<ChunkResult> chunks = splitter.split(document(page(1, null, text)), config(64, 0));

        assertThat(chunks).extracting(ChunkResult::getEstimatedTokens).containsExactly(expectedTokens);
    }

    private static ChunkConfig config(int chunkSize, int overlap) {
        return ChunkConfig.builder().chunkSize(chunkSize).chunkOverlap(overlap).build();
    }

    private static ParseResult.PageContent page(int number, String title, String text) {
        return ParseResult.PageContent.builder().pageNum(number).sectionTitle(title).text(text).build();
    }

    private static ParseResult document(ParseResult.PageContent... pages) {
        return ParseResult.builder().success(true).pages(List.of(pages)).totalPages(pages.length).build();
    }
}
