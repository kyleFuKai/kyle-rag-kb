package com.kyle.rag.repository;

import com.kyle.rag.entity.KnowledgeBase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
/**
 * 知识库仓库。
 * 实体带 @SoftDelete,已删行由 Hibernate 在所有查询里自动过滤——
 * 方法名里不用、也不能写 isDeleted(它不是实体属性,写了启动时 Spring Data 解析方法名会失败)。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/11/2 12:59
 */
public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBase, Long> {

    /** 某部门名下的知识库(全部知识库直接用 findAll) */
    List<KnowledgeBase> findByDepartmentId(String departmentId);

    /** 全员可见的公开知识库 */
    List<KnowledgeBase> findByIsPublicTrue();

    /** 批量查知识库(权限过滤时一次取回公开状态,避免逐个查) */
    List<KnowledgeBase> findByIdIn(List<Long> ids);

    /** 按名称 + 部门定位知识库,与 test-data.sql"按名称复用、不依赖主键"的口径一致 */
    Optional<KnowledgeBase> findByNameAndDepartmentId(String name, String departmentId);
}
