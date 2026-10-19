package com.kyle.rag.security;

import lombok.Data;
/**
 * 当前登录用户的 ThreadLocal 上下文。
 * 登录拦截器认证通过后 set()，业务代码随处 get() 取当前用户，
 * 请求结束时必须 clear() 防止线程复用导致串号/内存泄漏。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/10/19 20:08
 */
public class UserContext {

    private static final ThreadLocal<UserInfo> CONTEXT = new ThreadLocal<>();

    /** 拦截器 preHandle 里调用 */
    public static void set(UserInfo user) {
        CONTEXT.set(user);
    }

    /** 未登录（未初始化）时直接抛异常，强制调用方先过认证 */
    public static UserInfo get() {
        UserInfo user = CONTEXT.get();
        if (user == null) {
            throw new IllegalStateException("UserContext 未初始化，请检查认证拦截器配置");
        }
        return user;
    }

    public static Long getUserId() {
        return get().getUserId();
    }

    public static String getDepartmentId() {
        return get().getDepartmentId();
    }

    public static boolean isAdmin() {
        return "ADMIN".equals(get().getRole());
    }

    /** 拦截器 afterCompletion 里调用（Tomcat 线程会复用，不清会串号） */
    public static void clear() {
        CONTEXT.remove();
    }

    /** 当前用户信息载体 */
    @Data
    public static class UserInfo {
        /** 用户主键，关联 users 表 */
        private Long userId;
        /** 登录名，日志与展示用 */
        private String username;
        /** 所属部门，知识库按部门隔离时做数据过滤 */
        private String departmentId;
        /** 角色：ADMIN（管理员）/ MEMBER（普通成员） */
        private String role;
    }
}
