package com.kyle.rag.config;

import com.kyle.rag.security.KbAuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
/**
 * MVC 拦截器注册:把 KbAuthInterceptor 挂进 /api/**,排除登录接口 /api/v1/auth/**
 * (排除口径与 SecurityConfig 的 SaServletFilter 保持一致)。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/11/9 00:43
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final KbAuthInterceptor kbAuthInterceptor;

    /** 注册用户上下文拦截器;执行顺序:SaServletFilter(认证) → 本拦截器(写 UserContext) → Controller */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(kbAuthInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/v1/auth/**");
    }
}
