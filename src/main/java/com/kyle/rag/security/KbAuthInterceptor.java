package com.kyle.rag.security;

import cn.dev33.satoken.stp.StpUtil;
import com.kyle.rag.common.AuthConstants;
import com.kyle.rag.repository.KbPermissionRepository;
import com.kyle.rag.repository.KnowledgeBaseRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;
/**
 * 用户上下文拦截器:已登录请求把 userId/departmentId/role 从 Sa-Token Session 写入 UserContext,
 * 业务层(UserContext.getUserId/PermissionService)由此获得当前用户;请求结束 clear 防串号。
 * 依赖约定:登录时必须 StpUtil.getSession().set("departmentId"/"role", ...) 写入这两项,
 * 缺省兜底 department=""、role="MEMBER"(即非 admin,权限走显式授权)。
 * 未登录请求直接放行——由 SaServletFilter 统一拦截,本拦截器不重复报错。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/11/8 23:21
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class KbAuthInterceptor implements HandlerInterceptor {

    private final KnowledgeBaseRepository kbRepository;

    /** 已登录:从 Sa-Token Session 取用户信息写 UserContext;未登录:放行给 Sa-Token 过滤器 */
    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {
        if (!StpUtil.isLogin()) {
            return true;  // 未登录的请求让 Sa-Token Filter 处理
        }

        // 从 Sa-Token Session 获取用户信息，写入 ThreadLocal
        // 注意：登录时用 StpUtil.getSession().set() 存储，这里也要用 getSession().get() 读取
        String userId = String.valueOf(StpUtil.getLoginId());
        Object deptObj = StpUtil.getSession().get("departmentId");
        Object roleObj = StpUtil.getSession().get("role");

        String deptId = deptObj != null ? deptObj.toString() : "";
        String role = roleObj != null ? roleObj.toString() : AuthConstants.ROLE_MEMBER;

        UserContext.set(Long.parseLong(userId), deptId, role);
        return true;
    }

    /** 请求结束清 ThreadLocal(Tomcat 线程复用,不清会串号) */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserContext.clear();  // 请求结束后清理，防止内存泄漏
    }

}
