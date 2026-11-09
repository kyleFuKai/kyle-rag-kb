package com.kyle.rag.security;

import cn.dev33.satoken.context.SaHolder;
import cn.dev33.satoken.filter.SaServletFilter;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
/**
 * Sa-Token 全局鉴权配置:除 actuator 与登录接口外,所有 /api/** 必须登录。
 * 未登录返回 HTTP 401 + JSON 体(与 ApiResponse 的 code/message 字段对齐,前端统一解析)。
 * token 读写方式(Bearer Header / 1 天有效期)见 application.yml 的 sa-token 配置块。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/11/8 14:37
 */
@Configuration
@Slf4j
public class SecurityConfig {

    /** 全局过滤器:include /** 后按路由排除,再对 /api/** 强制登录;错误统一输出 401 JSON */
    @Bean
    public SaServletFilter saServletFilter() {
        return new SaServletFilter()
                .addInclude("/**")
                .addExclude("/actuator/**", "/api/v1/auth/**")
                .setAuth(obj -> {
                    // 所有 /api/** 接口都需要登录
                    SaRouter.match("/api/**", () -> StpUtil.checkLogin());
                })
                .setError(e -> {
                    SaHolder.getResponse().setStatus(HttpStatus.UNAUTHORIZED.value());
                    return "{\"code\":401,\"message\":\"请先登录\"}";
                });
    }
}
