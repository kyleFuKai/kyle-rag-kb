package com.kyle.rag.config;

import com.kyle.rag.dto.ApiResponse;
import com.kyle.rag.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
/**
 * 全局异常处理器:把散落的异常统一转为 ApiResponse 结构,前端不再见到 Spring 原生错误页。
 * 分层处理:BizException 按携带的 code 原样返回(业务语义);Bean Validation 失败 → 400;
 * Spring MVC 的请求层异常(路径/方法/类型不匹配)→ 404/405/400;其余 Exception 一律 500
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

    /** 参数校验失败(Bean Validation):返回 400 + 第一条校验错误信息 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(f -> f.getDefaultMessage())
                .orElse("参数校验失败");
        log.warn("[参数校验] {}", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST.value())
                .body(ApiResponse.error(HttpStatus.BAD_REQUEST.value(), message));
    }

    /** 请求路径不存在(Spring 6.1+ 静态资源未找到):404 而非兜底的 500 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResourceFound(NoResourceFoundException e) {
        log.warn("[请求异常] 路径不存在：{}", e.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND.value())
                .body(ApiResponse.error(HttpStatus.NOT_FOUND.value(), "接口不存在"));
    }

    /** 请求方法不支持(如用 GET 调 POST 接口):405 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("[请求异常] 方法不支持：{}", e.getMethod());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED.value())
                .body(ApiResponse.error(HttpStatus.METHOD_NOT_ALLOWED.value(), "请求方法不支持"));
    }

    /** 路径参数类型不匹配(如 /kb/abc):400 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("[请求异常] 参数类型不匹配：{}", e.getName());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST.value())
                .body(ApiResponse.error(HttpStatus.BAD_REQUEST.value(), "参数格式不正确"));
    }

    /** 请求体不可读(JSON 格式错误等):400 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("[请求异常] 请求体解析失败");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST.value())
                .body(ApiResponse.error(HttpStatus.BAD_REQUEST.value(), "请求体格式不正确"));
    }

    /** 缺少必填请求参数:400 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParam(MissingServletRequestParameterException e) {
        log.warn("[请求异常] 缺少参数：{}", e.getParameterName());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST.value())
                .body(ApiResponse.error(HttpStatus.BAD_REQUEST.value(), "缺少必填参数：" + e.getParameterName()));
    }

    /** 兜底:未预期异常一律 500,固定话术不泄露内部细节 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception e) {
        log.error("[系统异常] {}", e.getMessage(), e);
        return ResponseEntity.status(500)
                .body(ApiResponse.error(500, "系统内部错误，请稍后重试"));
    }
}
