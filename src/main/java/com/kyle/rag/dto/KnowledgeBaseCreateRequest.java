package com.kyle.rag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
/**
 * 创建知识库的请求体。isPublic 默认 false,即私有库,访问需经 kb_permission 授权。
 * departmentId 由前端传入:普通成员应只能在本部门(UserContext 的部门)建库,跨部门创建须在接口层按角色校验。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/11/2 13:40
 */
@Data
public class KnowledgeBaseCreateRequest {

    /** 知识库名称,必填;同部门内按名称定位(见 KnowledgeBaseRepository.findByNameAndDepartmentId) */
    @NotBlank(message = "知识库名称不能为空")
    @Size(max = 100, message = "知识库名称过长(最多 100 字)")
    private String name;

    /** 描述,可选 */
    private String description;

    /** 归属部门 ID,部门级数据隔离的依据 */
    @NotBlank(message = "归属部门不能为空")
    private String departmentId;

    /** 是否全员可见;默认私有 */
    private Boolean isPublic = false;
}
