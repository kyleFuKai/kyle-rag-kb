package com.kyle.rag.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库列表/详情的响应体。由实体字段 + 当前用户的权限计算结果组成,
 * permission 供前端渲染操作按钮(可见即可读,是否可写/管理看级别)。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/11/2 13:42
 */
@Data
@Builder
public class KnowledgeBaseVO {

    /** 知识库 ID */
    private Long id;

    /** 知识库名称 */
    private String name;

    /** 描述 */
    private String description;

    /** 归属部门 ID */
    private String departmentId;

    /** 是否全员可见;false 表示仅授权主体可见 */
    private Boolean isPublic;

    /** 创建者 userId */
    private Long createdBy;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 当前用户对该知识库的权限:ADMIN / WRITE / READ;无任何授权时为 null */
    private String permission;
}
