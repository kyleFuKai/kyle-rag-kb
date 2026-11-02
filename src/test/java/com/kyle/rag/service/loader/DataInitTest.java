package com.kyle.rag.service.loader;

import com.kyle.rag.entity.KbDocument;
import com.kyle.rag.repository.DocChunkRepository;
import com.kyle.rag.repository.KbDocumentRepository;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

/**
 *
 *
 * @author kyle
 * @version 1.0
 * @date 2026/11/2 00:09
 */
@SpringBootTest(useMainMethod = SpringBootTest.UseMainMethod.ALWAYS)
@ActiveProfiles("dev")
public class DataInitTest {

    @Autowired
    private KbDocumentRepository documentRepository;

    @Autowired
    private DocChunkRepository chunkRepository;

    @Test
    void verifyTestDataLoaded() {
        // ★ 坑 2：异步任务可能没跑完——用 Awaitility 轮询等待
        //   最多等 60s（要给 Embedding API + DB 写入留出时间）；
        //   每 2s 查一次，命中目标就立即返回。
        Awaitility.await()
                .atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofSeconds(2))
                .untilAsserted(() -> {
                    long docCount = documentRepository
                            .countByStatus(KbDocument.DocumentStatus.DONE);
                    assertThat(docCount).isGreaterThanOrEqualTo(3);
                });

        long docCount = documentRepository.countByStatus(KbDocument.DocumentStatus.DONE);
        long chunkCount = chunkRepository.count();

        assertThat(chunkCount).isGreaterThan(10);

        System.out.printf("已索引文档数：%d，分块总数：%d%n", docCount, chunkCount);
    }
}
