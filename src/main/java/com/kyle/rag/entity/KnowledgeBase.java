package com.kyle.rag.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SoftDelete;
import org.hibernate.annotations.SoftDeleteType;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
/**
 * 知识库实体，对应表 kb_knowledge_base。
 * 一个部门可有多个知识库；is_public=false 时按 kb_permission 授权访问。
 * 软删除由 @SoftDelete 接管：repository.delete() 自动置位 is_deleted，
 * 查询自动过滤已删行；恢复误删用手写 UPDATE 把标记改回 false。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/10/19 23:08
 */
@SoftDelete(strategy = SoftDeleteType.DELETED, columnName = "is_deleted")
@Entity
@Table(name = "kb_knowledge_base")
@Getter
@Setter
// 不用 @Data：其生成的 equals/hashCode 基于全部字段且随字段值变化，
// 实体进 Set/Map 后再改字段会"失踪"。JPA 实体用默认对象身份比较即可，
// 真需要按 id 去重时用 Map<Long, 实体>
@ToString(onlyExplicitlyIncluded = true)
public class KnowledgeBase {

    /** 主键，BIGSERIAL 自增 */
    @ToString.Include
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 知识库名称 */
    @ToString.Include
    @Column(nullable = false, length = 100)
    private String name;

    /** 知识库描述 */
    @Column(columnDefinition = "TEXT")
    private String description;

    /** 归属部门ID，部门级数据隔离的依据 */
    @Column(name = "department_id", nullable = false, length = 50)
    private String departmentId;

    /** 是否对所有人开放；false 时按 kb_permission 授权访问 */
    @Column(name = "is_public", nullable = false)
    private Boolean isPublic = false;

    /** 创建者 userId */
    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    /** 创建时间，入库时 Hibernate 自动填充 */
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 更新时间，每次 UPDATE 由 Hibernate 自动刷新 */
    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
