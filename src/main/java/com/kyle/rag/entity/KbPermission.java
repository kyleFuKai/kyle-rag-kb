package com.kyle.rag.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
/**
 * 知识库授权实体,对应表 kb_permission。
 * 私有知识库(KnowledgeBase.isPublic=false)的访问依据:一条记录 = 一个主体(用户或部门)对一个知识库的权限级别,
 * (kb_id, subject_type, subject_id) 在表上唯一——同一主体重复授权应更新 permission 而非新增。
 * 公开知识库不走本表;撤销授权即物理删除(本表无软删除)。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/11/2 11:00
 */
@Entity
@Table(name = "kb_permission")
@Getter
@Setter
@ToString(onlyExplicitlyIncluded = true)
public class KbPermission {

    /** 主键,BIGSERIAL 自增 */
    @ToString.Include
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 被授权的知识库 ID(裸 id 关联,无外键) */
    @ToString.Include
    @Column(name = "kb_id", nullable = false)
    private Long kbId;

    /** 授权主体类型:USER / DEPARTMENT,决定 subjectId 按用户还是部门解释 */
    @ToString.Include
    @Column(name = "subject_type", nullable = false, length = 20)
    private String subjectType;

    /** 授权主体 ID:部门 ID 原样存,用户 ID 转成字符串存(列是 VARCHAR,两种主体共用) */
    @ToString.Include
    @Column(name = "subject_id", nullable = false, length = 50)
    private String subjectId;

    /** 权限级别:READ(检索问答)/ WRITE(上传文档)/ ADMIN(管理知识库与授权);高级别是否覆盖低级别由校验逻辑定义 */
    @ToString.Include
    @Column(name = "permission", nullable = false, length = 20)
    private String permission;

    /** 授权人 userId */
    @Column(name = "granted_by", nullable = false)
    private Long grantedBy;

    /** 授权时间,入库时 Hibernate 自动填充 */
    @CreationTimestamp
    @Column(name = "granted_at")
    private LocalDateTime grantedAt;
}
