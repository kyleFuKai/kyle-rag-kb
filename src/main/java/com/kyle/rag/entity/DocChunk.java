package com.kyle.rag.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.Array;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
/**
 * 文档分块实体，对应表 kb_doc_chunk，RAG 检索的最小单元。
 * 一条记录 = 一段原文 + 一个语义向量 + 一个全文检索向量。
 * 注意：content_tsv 列故意不映射，由数据库触发器自动维护；
 * 向量列依赖 pom 里的 hibernate-vector 模块。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/10/19 23:18
 */
@Entity
@Table(name = "kb_doc_chunk")
@Getter
@Setter
// 不用 @Data：equals/hashCode 随字段变化会让实体进集合后"失踪"，理由同 KnowledgeBase
// 检索融合去重时用 Map<Long, DocChunk> 按 id 做，不要放 HashSet
@ToString(onlyExplicitlyIncluded = true)
public class DocChunk {

    /** 主键，BIGSERIAL 自增 */
    @ToString.Include
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属文档ID（裸 id 关联，无外键） */
    @ToString.Include
    @Column(name = "doc_id", nullable = false)
    private Long docId;

    /** 所属知识库ID（冗余存储，检索时按 kb_id 过滤免 JOIN） */
    @Column(name = "kb_id", nullable = false)
    private Long kbId;

    /** 在文档中的顺序（0-based），还原原文时按它排序拼接 */
    @ToString.Include
    @Column(name = "chunk_index", nullable = false)
    private Integer chunkIndex;

    /** 分块原文 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /**
     * 语义向量，1024 维，配百炼 text-embedding-v4。
     * hibernate-vector 原生映射 pgvector 的 vector 类型；
     * 检索走表上的 HNSW 索引（余弦距离）
     */
    @JdbcTypeCode(SqlTypes.VECTOR)
    @Array(length = 1024)
    @Column(name = "embedding", columnDefinition = "vector(1024)")
    private float[] embedding;

    /** 来自文档第几页（PDF 专用，其他类型为 null），引用来源展示用 */
    @Column(name = "page_num")
    private Integer pageNum;

    /** 所在章节标题（能识别时），拼 prompt 和引用展示用 */
    @Column(name = "section_title", length = 500)
    private String sectionTitle;

    /** 该块的 Token 估算数，jtokkit 切分时算好写入 */
    @Column(name = "token_count", nullable = false)
    private Integer tokenCount = 0;

    /** 所属文档版本号（冗余），重建索引后按 doc_id + 旧版本号删旧分块 */
    @Column(name = "doc_version", nullable = false)
    private Integer docVersion;

    /** 创建时间，入库时 Hibernate 自动填充 */
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
