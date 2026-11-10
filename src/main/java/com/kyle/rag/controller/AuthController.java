package com.kyle.rag.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.kyle.rag.dto.ApiResponse;
import com.kyle.rag.entity.User;
import com.kyle.rag.exception.BizException;
import com.kyle.rag.repository.UserRepository;
import com.kyle.rag.common.AuthConstants;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import jakarta.annotation.PostConstruct;
import org.springframework.web.bind.annotation.*;

/**
 * 登录接口(/api/v1/auth):用户名密码校验(users 表,BCrypt),发 Sa-Token。
 * 生产中应对接企业 SSO / LDAP,本实现为演示版;
 * 登录成功后 departmentId / role 写入 Sa-Token Session,供 KbAuthInterceptor 读取。
 * 演示用户(hr001/tech001/admin,密码均 demo123)在应用启动时自动播种到 users 表。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/11/9 13:16
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    /** 登录:查 users 表 → BCrypt 校验 → StpUtil.login 发 token → 附加信息写 Session */
    @PostMapping("/login")
    public ApiResponse<String> login(@RequestBody LoginRequest req) {
        User user = userRepository.findByUsername(req.getUsername())
                .orElseThrow(() -> BizException.badRequest("用户名或密码错误"));
        if (!passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            throw BizException.badRequest("用户名或密码错误");
        }

        StpUtil.login(user.getId());
        // 附加信息存 Session,拦截器(KbAuthInterceptor)从这里读取
        StpUtil.getSession().set("departmentId", user.getDepartmentId());
        StpUtil.getSession().set("role", user.getRole());

        log.info("[Auth] 登录成功：username={}，userId={}，dept={}",
                user.getUsername(), user.getId(), user.getDepartmentId());
        return ApiResponse.ok(StpUtil.getTokenValue());
    }

    /** 登出:注销当前 token(会话与附加信息一并失效) */
    @PostMapping("/logout")
    public ApiResponse<Void> logout() {
        StpUtil.logout();
        return ApiResponse.ok(null);
    }

    /** 演示用户播种:users 表缺谁补谁;密码统一 demo123,BCrypt 加密存储 */
    @PostConstruct
    void seedDemoUsers() {
        seedOne("hr001",   "HR",   AuthConstants.ROLE_MEMBER);
        seedOne("tech001", "TECH", AuthConstants.ROLE_MEMBER);
        seedOne("admin",   "ALL",  AuthConstants.ROLE_ADMIN);
    }

    private void seedOne(String username, String departmentId, String role) {
        if (userRepository.findByUsername(username).isPresent()) return;
        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode("demo123"));
        user.setDepartmentId(departmentId);
        user.setRole(role);
        userRepository.save(user);
        log.info("[Auth] 演示用户已播种：{}", username);
    }

    /** 登录请求体 */
    @Data
    public static class LoginRequest {
        /** 登录名 */
        private String username;
        /** 明文密码(演示环境,生产应对接 SSO) */
        private String password;
    }
}
