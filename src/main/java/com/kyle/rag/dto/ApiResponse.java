package com.kyle.rag.dto;

import lombok.Data;

/**
 * 接口统一响应包装:所有 Controller 返回值都套这一层,前端按 code 判断成败、从 data 取业务数据。
 * code 沿用 HTTP 状态码语义(200 成功),业务错误码的区间约定待全局异常处理器落地时一并定。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/11/2 16:28
 */
@Data
public class ApiResponse<T> {

    /** 结果码,200 成功;非 200 时 data 为空、message 给出原因 */
    private int code;

    /** 提示信息,成功固定 "success",失败为可直接展示给用户的文案 */
    private String message;

    /** 业务数据,泛型由各接口决定;失败时为 null */
    private T data;

    /** 成功响应 */
    public static <T> ApiResponse<T> ok(T data) {
        ApiResponse<T> r = new ApiResponse<>();
        r.setCode(200);
        r.setMessage("success");
        r.setData(data);
        return r;
    }

    /** 失败响应,不带 data;供全局异常处理器与业务校验失败时使用 */
    public static <T> ApiResponse<T> error(int code, String message) {
        ApiResponse<T> r = new ApiResponse<>();
        r.setCode(code);
        r.setMessage(message);
        return r;
    }
}
