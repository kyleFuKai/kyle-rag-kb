package com.kyle.rag.security;

import lombok.Data;
/**
 * 当前登录用户的 ThreadLocal 上下文(三个独立槽位:userId / departmentId / role)。
 * 写入方两类:HTTP 请求由登录拦截器 set、请求结束 clear;索引链路由 IndexTaskLauncher
 * 在池线程 set 快照、finally clear。线程复用场景不清除会串号/内存泄漏。
 *
 * ⚠️ 各槽位带 withInitial 默认值:未初始化时返回开发期默认身份(userId=1 / admin)而非抛异常,
 * 是认证拦截器落地前跑通全链路的脚手架——上线前必须移除默认值或改回未初始化即失败,
 * 否则任何绕过认证的路径都会以管理员身份执行、用量记到 1 号用户。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/10/19 20:08
 */
public class UserContext {

    /** 未初始化默认 1L——开发期脚手架,上线前应移除(见类注释) */
    private static final ThreadLocal<Long> USER_ID = ThreadLocal.withInitial(() -> 1L);
    /** 未初始化默认 "default"——同上 */
    private static final ThreadLocal<String> DEPARTMENT_ID = ThreadLocal.withInitial(() -> "default");
    /** 默认 admin 仅限开发期;生产默认角色应是最低权限,未认证 ≠ 管理员 */
    private static final ThreadLocal<String> ROLE = ThreadLocal.withInitial(() -> "admin");

    /** 未 set 时返回默认 1L(开发期),不抛异常 */
    public static Long getUserId() {
        return USER_ID.get();
    }

    /** 未 set 时返回 "default" */
    public static String getDepartmentId() {
        return DEPARTMENT_ID.get();
    }

    /** 未 set 时返回 "admin"(开发期默认) */
    public static String getRole() {
        return ROLE.get();
    }

    /** 忽略大小写比较,兼容 "ADMIN"/"admin" 两种存法 */
    public static boolean isAdmin() {
        return "ADMIN".equalsIgnoreCase(ROLE.get());
    }

    /** 三个槽位一次性 set,保证快照一致;调用方负责在同一线程的任务结束时 clear */
    public static void set(Long userId, String departmentId, String role) {
        USER_ID.set(userId);
        DEPARTMENT_ID.set(departmentId);
        ROLE.set(role);
    }

    /** 三个槽位必须全部 remove,漏掉任何一个都会把身份串给该线程的下一个任务 */
    public static void clear() {
        USER_ID.remove();
        DEPARTMENT_ID.remove();
        ROLE.remove();
    }
}
