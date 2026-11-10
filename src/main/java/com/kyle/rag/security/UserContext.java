package com.kyle.rag.security;

import com.kyle.rag.common.AuthConstants;

/**
 * 当前登录用户的 ThreadLocal 上下文(三个独立槽位:userId / departmentId / role)。
 * 写入方两类:HTTP 请求由登录拦截器 set、请求结束 clear;索引链路由 IndexTaskLauncher
 * 在池线程 set 快照、finally clear。线程复用场景不清除会串号/内存泄漏。
 *
 * 各槽位无初始值:未初始化时 get 抛 IllegalStateException(fail-fast),
 * 杜绝"未认证请求以默认 admin 身份执行"的隐患——能取到身份的请求必然走过认证。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/10/19 20:08
 */
public class UserContext {

    /** 各槽位共用:未 set 时抛异常,强制调用方先过认证 */
    private static <T> T require(ThreadLocal<T> slot, String name) {
        T value = slot.get();
        if (value == null) {
            throw new IllegalStateException("UserContext." + name + " 未初始化，请检查认证拦截器配置");
        }
        return value;
    }

    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> DEPARTMENT_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> ROLE = new ThreadLocal<>();

    /** 取当前用户 ID;未认证(未 set)时抛 IllegalStateException */
    public static Long getUserId() {
        return require(USER_ID, "userId");
    }

    /** 取当前用户部门;未认证时抛 IllegalStateException */
    public static String getDepartmentId() {
        return require(DEPARTMENT_ID, "departmentId");
    }

    /** 取当前用户角色;未认证时抛 IllegalStateException */
    public static String getRole() {
        return require(ROLE, "role");
    }

    /** 忽略大小写比较,兼容 "ADMIN"/"admin" 两种存法 */
    public static boolean isAdmin() {
        return AuthConstants.PERM_ADMIN.equalsIgnoreCase(ROLE.get());
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
