package com.kyle.rag.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.kyle.rag.dto.ApiResponse;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
/**
 * 测试用的登录接口。
 * 生产中应对接企业 SSO / LDAP，这里简化处理
 *
 * @author kyle
 * @version 1.0
 * @date 2026/11/9 13:16
 */
@RestController
@RequestMapping("/api/v1/auth")
@Slf4j
public class AuthController {

    // 模拟用户数据（生产中从数据库查）
    private static final Map<String, UserProfile> DEMO_USERS = Map.of(
            "hr001",   new UserProfile(1L, "HR",   "MEMBER"),
            "tech001", new UserProfile(2L, "TECH", "MEMBER"),
            "admin",   new UserProfile(3L, "ALL",  "ADMIN")
    );

    /** 登录:校验演示用户 → StpUtil.login 发 token → departmentId/role 写入 Sa-Token Session(供拦截器读取) */
    @PostMapping("/login")
    public ApiResponse<String> login(@RequestBody LoginRequest req) {
        UserProfile profile = DEMO_USERS.get(req.getUsername());
        if (profile == null || !"demo123".equals(req.getPassword())) {
            return ApiResponse.error(401, "用户名或密码错误");
        }

        StpUtil.login(profile.userId());
        // 把用户附加信息存入 Session，Interceptor 通过 StpUtil.getSession().get() 读取
        StpUtil.getSession().set("departmentId", profile.departmentId());
        StpUtil.getSession().set("role", profile.role());

        String token = StpUtil.getTokenValue();
        log.info("[Auth] 登录成功：username={}，userId={}，dept={}",
                req.getUsername(), profile.userId(), profile.departmentId());

        return ApiResponse.ok(token);
    }

    /** 登出:注销当前 token(会话与 Session 内附加信息一并失效) */
    @PostMapping("/logout")
    public ApiResponse<Void> logout() {
        StpUtil.logout();
        return ApiResponse.ok(null);
    }

    @Data
    static class LoginRequest {
        private String username;
        private String password;
    }

    record UserProfile(Long userId, String departmentId, String role) {}
}
