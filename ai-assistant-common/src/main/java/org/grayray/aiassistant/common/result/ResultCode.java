package org.grayray.aiassistant.common.result;


import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ResultCode {
    SUCCESS(0, "success"),
    BAD_REQUEST(400, "请求参数错误"),
    UNAUTHORIZED(401, "未授权"),
    FORBIDDEN(403, "禁止访问"),
    NOT_FOUND(404, "资源不存在"),
    INTERNAL_ERROR(500, "系统内部错误"),
    // AI / SSE 相关可扩展
    AI_STREAM_ERROR(5001, "AI 流式响应异常"),
    ;

    private final Integer code;
    private final String message;
}
