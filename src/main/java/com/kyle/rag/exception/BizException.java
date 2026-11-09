package com.kyle.rag.exception;

import lombok.Getter;
/**
 * 业务异常:携带 HTTP 语义的状态码,由全局异常处理器(@RestControllerAdvice,待建)
 * 统一捕获并转为 ApiResponse.error(code, message) 返回前端。
 * 业务代码抛本异常代替裸 RuntimeException,即可获得结构化错误响应。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/11/8 14:45
 */
@Getter
public class BizException extends RuntimeException {

    /** HTTP 语义状态码:400 参数错 / 403 无权 / 404 不存在;由 advice 原样写回响应 */
    private final int code;

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    /** 便捷工厂:403 */
    public static BizException forbidden(String message) {
        return new BizException(403, message);
    }

    /** 便捷工厂:404 */
    public static BizException notFound(String message) {
        return new BizException(404, message);
    }

    /** 便捷工厂:400 */
    public static BizException badRequest(String message) {
        return new BizException(400, message);
    }
}
