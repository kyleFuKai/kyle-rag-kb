package com.kyle.rag.repository;

import com.kyle.rag.entity.KbDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
/**
 * 文档数据访问接口，继承 JpaRepository 提供的增删改查和分页能力。
 * 实体类型为 KbDocument，主键类型为 Long；软删除过滤由实体上的 @SoftDelete 统一处理。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/10/27 10:55
 */
public interface KbDocumentRepository extends JpaRepository<KbDocument, Long> {

    /** 按知识库 ID 查询未删除的文档，返回结果未指定排序。 */
    List<KbDocument> findByKbId(Long kbId);

    /**
     * 条件软删(幂等,并发安全):直接 UPDATE 置 is_deleted=true,不加载实体。
     * WHERE 带 is_deleted=false:已被并发请求软删时影响 0 行,不会抛乐观锁异常。
     * 返回影响行数(0=已被删除)。
     */
    @Modifying
    @Transactional
    @Query(value = "UPDATE kb_document SET is_deleted = true WHERE id = :id AND is_deleted = false", nativeQuery = true)
    int softDeleteById(@Param("id") Long id);

    /**
     * 统计所有知识库中指定索引状态的未删除文档数量。
     * JPQL 使用实体名 KbDocument 和属性名 status，而不是数据库表名和列名。
     */
    @Query("SELECT COUNT(d) FROM KbDocument d WHERE d.status = :status")
    long countByStatus(KbDocument.DocumentStatus status);
}
