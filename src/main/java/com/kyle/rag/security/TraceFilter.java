package com.kyle.rag.security;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;
/**
 * 请求追踪过滤器：为每个请求生成 traceId,注入 MDC 供日志使用
 * (application.yml 日志格式的 [%X{traceId}] 占位符由它填充),并回写 X-Trace-Id 响应头,
 * 前端报障时可凭该 ID 反查服务端日志。客户端可自带 X-Trace-Id(分布式追踪串联)。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/11/10 00:55
 */
@Component
public class TraceFilter implements Filter{

    private static final String TRACE_ID_HEADER = "X-Trace-Id";

    /** 生成/继承 traceId → MDC(日志) + 响应头(前端回查);finally 保证 MDC 清理 */
    @Override
    public void doFilter(ServletRequest request, ServletResponse response,
                         FilterChain chain) throws IOException, ServletException {
        HttpServletRequest httpReq = (HttpServletRequest) request;

        // 优先使用客户端传入的 traceId（方便分布式追踪）
        String traceId = httpReq.getHeader(TRACE_ID_HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().substring(0, 8);
        }

        MDC.put("traceId", traceId);
        if (response instanceof jakarta.servlet.http.HttpServletResponse httpResp) {
            httpResp.setHeader(TRACE_ID_HEADER, traceId);
        }
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("traceId");
        }
    }
}
