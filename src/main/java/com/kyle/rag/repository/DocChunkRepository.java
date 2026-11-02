package com.kyle.rag.repository;

import com.kyle.rag.entity.DocChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
/**
 *
 *
 * @author kyle
 * @version 1.0
 * @date 2026/11/1 23:18
 */
public interface DocChunkRepository extends JpaRepository<DocChunk, Long> {

    /** 删除文档的旧版本分块（重建索引时使用） */
    @Modifying
    @Transactional
    @Query("DELETE FROM DocChunk c WHERE c.docId = :docId AND c.docVersion < :version")
    void deleteByDocIdAndDocVersionLessThan(@Param("docId") Long docId,
                                            @Param("version") Integer version);

    /**
     * 向量相似度检索（余弦相似度）。
     * 使用 PGVector 的 <=> 操作符（余弦距离），ORDER BY 距离升序即按相似度降序。
     *
     * 关键过滤条件：
     *   1. c.doc_version = d.version —— 只取每个文档的"当前版本" chunk
     *      重建索引时 doIndex 是"先写新版本、再删旧版本"，中间窗口里 DB 同时存在新旧版本 chunk。
     *      用 doc_version = d.version 自动屏蔽旧版本，让用户查询不会命中"半新半旧"的混合结果。
     *   2. d.is_deleted = FALSE —— 软删除的文档不参与召回
     *      避免"已删除文档"的 chunk 成为幽灵数据。
     *
     * 注意：不能在 SELECT 中直接计算 score 并返回，Hibernate 6.x 严格映射会因
     * 结果集多出 score 列而报错。排序放在 ORDER BY，分数由调用方用 RRF 融合处理。
     */
    @Query(value = """
            SELECT c.*
            FROM kb_doc_chunk c
            JOIN kb_document  d ON c.doc_id = d.id
            WHERE c.kb_id = :kbId
              AND c.doc_version = d.version
              AND d.is_deleted = FALSE
            ORDER BY c.embedding <=> CAST(:embedding AS vector)
            LIMIT :topK
            """, nativeQuery = true)
    List<DocChunk> findByVectorSimilarity(
            @Param("kbId") Long kbId,
            @Param("embedding") String embedding,   // PGVector 格式字符串：[0.1,0.2,...]
            @Param("topK") int topK);

    /**
     * 全文检索（PostgreSQL 全文搜索）。
     * 使用 to_tsquery('simple', :query) 匹配 content_tsv。
     * simple 配置不进行词干化，适合中文分词后的关键词检索。
     *
     * 过滤条件同 findByVectorSimilarity：
     *   1. c.doc_version = d.version —— 只召回当前版本 chunk
     *   2. d.is_deleted = FALSE —— 过滤软删除文档
     *
     * 注意：不能在 SELECT 中包含 ts_rank(...) AS score，原因同上。
     * 全文检索的排序意义在于确保最相关结果在前，实际分数在 RRF 融合阶段按排名计算。
     */
    @Query(value = """
            SELECT c.*
            FROM kb_doc_chunk c
            JOIN kb_document  d ON c.doc_id = d.id
            WHERE c.kb_id = :kbId
              AND c.doc_version = d.version
              AND d.is_deleted = FALSE
              AND c.content_tsv @@ to_tsquery('simple', :tsQuery)
            ORDER BY ts_rank(c.content_tsv, to_tsquery('simple', :tsQuery)) DESC
            LIMIT :topK
            """, nativeQuery = true)
    List<DocChunk> findByFullTextSearch(
            @Param("kbId") Long kbId,
            @Param("tsQuery") String tsQuery,       // 例如："技术 & 规范 & 接口"
            @Param("topK") int topK);

    /** 按文档 ID 查询所有分块 */
    List<DocChunk> findByDocId(Long docId);

    /** 按文档 ID 删除所有分块（删除文档时清理） */
    @Modifying
    @Transactional
    @Query("DELETE FROM DocChunk c WHERE c.docId = :docId")
    void deleteByDocId(@Param("docId") Long docId);

    /** 按 ID 列表批量查询（引用溯源时使用） */
    @Query("SELECT c FROM DocChunk c WHERE c.id IN :ids")
    List<DocChunk> findByIds(@Param("ids") List<Long> ids);
}
