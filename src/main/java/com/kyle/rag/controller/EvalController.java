package com.kyle.rag.controller;

import com.kyle.rag.dto.ApiResponse;
import com.kyle.rag.dto.EvalReport;
import com.kyle.rag.entity.DocChunk;
import com.kyle.rag.entity.EvalDataset;
import com.kyle.rag.repository.DocChunkRepository;
import com.kyle.rag.repository.EvalDatasetRepository;
import com.kyle.rag.security.UserContext;
import com.kyle.rag.exception.BizException;
import com.kyle.rag.security.UserContext;
import com.kyle.rag.service.EvalService;
import com.kyle.rag.service.PermissionService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
/**
 * 评估与数据集管理的 REST 入口(/api/v1/eval):触发评估、历史对比、
 * 数据集 CRUD、chunk 标注查询(为 expectedChunkIds 回填服务)。
 * 标注工作流:GET /chunks 浏览分块 → 挑相关 chunkId → POST/PUT 数据集回填 → POST /run 评估。
 * 同步接口:一轮评估含多次模型调用,题多时耗时分钟级。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/11/10 00:14
 */
@RestController
@RequestMapping("/api/v1/eval")
@RequiredArgsConstructor
public class EvalController {

    private final EvalService evalService;
    private final EvalDatasetRepository datasetRepository;
    private final DocChunkRepository chunkRepository;
    private final PermissionService permissionService;

    /** 触发评估(管理员专用,同步执行);version 缺省 latest,建议传语义化标识(如 v2_reranker_on) */
    @PostMapping("/{kbId}/run")
    public ApiResponse<EvalReport> runEval(
            @PathVariable Long kbId,
            @RequestParam(defaultValue = "latest") String version) {
        requireAdmin();
        EvalReport report = evalService.runEvaluation(kbId, version);
        return ApiResponse.ok(report);
    }

    /** 历史评估对比:按 evalVersion 聚合各轮指标,按最近评估时间倒序 */
    @GetMapping("/{kbId}/history")
    public ApiResponse<List<EvalReport>> getHistory(@PathVariable Long kbId) {
        permissionService.requireRead(kbId);
        return ApiResponse.ok(evalService.compareVersions(kbId));
    }

    // ==================== 评估数据集管理 ====================

    /** 查询知识库的评估数据集(全部条目) */
    @GetMapping("/{kbId}/dataset")
    public ApiResponse<List<EvalDataset>> listDataset(@PathVariable Long kbId) {
        permissionService.requireRead(kbId);
        return ApiResponse.ok(datasetRepository.findByKbId(kbId));
    }

    /** 新增评估问题(仅管理员);expectedChunkIds 可后置回填(先用 /chunks 浏览标注) */
    @PostMapping("/{kbId}/dataset")
    public ApiResponse<EvalDataset> addQuestion(
            @PathVariable Long kbId,
            @RequestBody EvalDatasetRequest req) {
        requireAdmin();
        EvalDataset item = new EvalDataset();
        item.setKbId(kbId);
        item.setQuestion(req.getQuestion());
        item.setExpectedAnswer(req.getExpectedAnswer());
        item.setExpectedChunkIds(req.getExpectedChunkIds());
        item.setCreatedBy(UserContext.getUserId());
        return ApiResponse.ok(datasetRepository.save(item));
    }

    /** 更新评估问题(仅管理员):三字段均为可选,null 字段不覆盖(局部更新语义) */
    @PutMapping("/{kbId}/dataset/{id}")
    public ApiResponse<EvalDataset> updateQuestion(
            @PathVariable Long kbId,
            @PathVariable Long id,
            @RequestBody EvalDatasetRequest req) {
        requireAdmin();
        EvalDataset item = datasetRepository.findById(id)
                .filter(d -> d.getKbId().equals(kbId))   // 数据集必须属于路径中的 kbId,防跨库改动
                .orElseThrow(() -> BizException.notFound("评估数据不存在"));
        if (req.getQuestion() != null) item.setQuestion(req.getQuestion());
        if (req.getExpectedAnswer() != null) item.setExpectedAnswer(req.getExpectedAnswer());
        if (req.getExpectedChunkIds() != null) item.setExpectedChunkIds(req.getExpectedChunkIds());
        return ApiResponse.ok(datasetRepository.save(item));
    }

    /** 删除评估问题(仅管理员);历史 EvalResult 保留(datasetId 悬挂引用,聚合查询 JOIN 后自然失效) */
    @DeleteMapping("/{kbId}/dataset/{id}")
    public ApiResponse<Void> deleteQuestion(
            @PathVariable Long kbId,
            @PathVariable Long id) {
        requireAdmin();
        EvalDataset item = datasetRepository.findById(id)
                .filter(d -> d.getKbId().equals(kbId))
                .orElseThrow(() -> BizException.notFound("评估数据不存在"));
        datasetRepository.delete(item);
        return ApiResponse.ok(null);
    }

    // ==================== Chunk 查询（用于标注 expectedChunkIds） ====================

    /** 查询知识库全部分块(200 字摘要,不含向量),标注 expectedChunkIds 的浏览来源 */
    @GetMapping("/{kbId}/chunks")
    public ApiResponse<List<ChunkSummary>> listChunks(@PathVariable Long kbId) {
        permissionService.requireRead(kbId);
        List<DocChunk> chunks = chunkRepository.findByKbId(kbId);
        List<ChunkSummary> summaries = chunks.stream().map(c -> {
            ChunkSummary s = new ChunkSummary();
            s.setId(c.getId());
            s.setDocId(c.getDocId());
            s.setChunkIndex(c.getChunkIndex());
            s.setContent(c.getContent().length() > 200
                    ? c.getContent().substring(0, 200) + "..."
                    : c.getContent());
            s.setTokenCount(c.getTokenCount());
            return s;
        }).toList();
        return ApiResponse.ok(summaries);
    }

    /**
     * 评估触发与数据集写操作仅限 ADMIN 角色。
     * 评估数据集是检索质量的基准(标准问题 + 期望命中的 chunk),
     * 能改动它就能操纵 hitRate/MRR 的结论——所以不按库的 WRITE 权限放行,只给管理员。
     */
    private void requireAdmin() {
        if (!UserContext.isAdmin()) {
            throw BizException.forbidden("仅管理员可执行该操作");
        }
    }

    @Data
    public static class EvalDatasetRequest {
        private String question;
        private String expectedAnswer;
        private Long[] expectedChunkIds;
    }

    @Data
    public static class ChunkSummary {
        private Long id;
        private Long docId;
        private Integer chunkIndex;
        private String content;
        private Integer tokenCount;
    }
}
