package com.kyle.rag.common;

/**
 * 鉴权与权限相关的字符串常量:DB 以字符串存取这些值(无枚举映射),散落多处使用,
 * 统一收敛到此类避免拼写漂移。新增权限级别/角色时在此处定义。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/11/10
 */
public final class AuthConstants {

    private AuthConstants() {}

    // ===== kb_permission.subject_type =====
    /** 授权主体:用户 */
    public static final String SUBJECT_USER = "USER";
    /** 授权主体:部门 */
    public static final String SUBJECT_DEPARTMENT = "DEPARTMENT";

    // ===== kb_permission.permission / UserContext.role =====
    /** 权限级别:只读 */
    public static final String PERM_READ = "READ";
    /** 权限级别:可写(含上传/删除文档) */
    public static final String PERM_WRITE = "WRITE";
    /** 权限级别:管理(含授权管理) */
    public static final String PERM_ADMIN = "ADMIN";

    // ===== ChatMessage.role =====
    /** 消息角色:用户提问 */
    public static final String CHAT_ROLE_USER = "USER";
    /** 消息角色:模型回答 */
    public static final String CHAT_ROLE_ASSISTANT = "ASSISTANT";

    // ===== 用户角色(UserContext.role / 登录用户) =====
    /** 管理员:全库访问 + 评估/数据集管理 */
    public static final String ROLE_ADMIN = "ADMIN";
    /** 普通成员:按 kb_permission 授权访问 */
    public static final String ROLE_MEMBER = "MEMBER";
}
