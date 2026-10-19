package com.kyle.rag.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SoftDelete;
import org.hibernate.annotations.SoftDeleteType;

import java.time.LocalDateTime;
/**
 * 文档实体，对应表 kb_document。
 * 一个知识库（kbId）下有多个文档，一个文档解析切分后有多个分块（KbDocChunk）。
 * 软删除由 @SoftDelete 接管：repository.delete() 自动置位 is_deleted，
 * 查询自动过滤已删行；恢复误删用手写 UPDATE 把标记改回 false。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/10/19 23:13
 */
@SoftDelete(strategy = SoftDeleteType.DELETED, columnName = "is_deleted")
@Entity
@Table(name = "kb_document")
@Getter
@Setter
// 不用 @Data：equals/hashCode 随字段变化会让实体进集合后"失踪"，理由同 KnowledgeBase
@ToString(onlyExplicitlyIncluded = true)
public class KbDocument {

    /** 主键，BIGSERIAL 自增 */
    @ToString.Include
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属知识库ID（裸 id 关联，无外键） */
    @Column(name = "kb_id", nullable = false)
    private Long kbId;

    /** 原始文件名 */
    @ToString.Include
    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    /** 文件类型：PDF / DOCX / MD / TXT */
    @Column(name = "file_type", nullable = false, length = 20)
    private String fileType;

    /** 文件字节数 */
    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    /** MinIO 中的对象路径 */
    @Column(name = "minio_path", nullable = false, length = 500)
    private String minioPath;

    /** 索引状态：上传时 PENDING，索引任务开始转 PROCESSING，成功 DONE，失败 FAILED（失败原因见 errorMsg） */
    @ToString.Include
    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private DocumentStatus status = DocumentStatus.PENDING;

    /** 索引失败原因，仅 FAILED 状态有值 */
    @Column(name = "error_msg", columnDefinition = "TEXT")
    private String errorMsg;

    /** 当前版本的分块数量，索引完成后回填 */
    @Column(name = "chunk_count")
    private Integer chunkCount = 0;

    /** 向量化消耗的 Token 总数，索引完成后回填 */
    @Column(name = "token_count")
    private Integer tokenCount = 0;

    /** 版本号，重建索引 +1；旧版本分块按版本号识别后删除 */
    @Column(nullable = false)
    private Integer version = 1;

    /** 上传者 userId */
    @Column(name = "uploaded_by", nullable = false)
    private Long uploadedBy;

    /** 上传时间，入库时 Hibernate 自动填充 */
    @CreationTimestamp
    @Column(name = "uploaded_at", updatable = false)
    private LocalDateTime uploadedAt;

    /** 最近一次索引完成时间 */
    @Column(name = "indexed_at")
    private LocalDateTime indexedAt;

    /** 文档索引状态枚举，值存库为字符串 */
    public enum DocumentStatus {
        PENDING, PROCESSING, DONE, FAILED
    }
}
