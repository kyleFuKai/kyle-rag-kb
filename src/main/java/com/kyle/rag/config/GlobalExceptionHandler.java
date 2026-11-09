package com.kyle.rag.config;

import com.kyle.rag.dto.ApiResponse;
import com.kyle.rag.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
/**
 * 全局异常处理器:把散落的异常统一转为 ApiResponse 结构,前端不再见到 Spring 原生错误页。
 * 两层处理:BizException 按携带的 code 原样返回(业务语义);其余 Exception 一律 500
 * 且不外泄异常细节(堆栈只进日志)。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/11/8 14:49
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /** 业务异常:按 BizException.code 设置 HTTP 状态,message 透传给前端 */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBizException(BizException e) {
        log.warn("[业务异常] code={}，message={}", e.getCode(), e.getMessage());
        return ResponseEntity.status(e.getCode())
                .body(ApiResponse.error(e.getCode(), e.getMessage()));
    }

    /** 兜底:未预期异常一律 500,固定话术不泄露内部细节 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception e) {
        log.error("[系统异常] {}", e.getMessage(), e);
        return ResponseEntity.status(500)
                .body(ApiResponse.error(500, "系统内部错误，请稍后重试"));
    }
}
