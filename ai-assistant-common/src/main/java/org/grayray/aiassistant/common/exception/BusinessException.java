package org.grayray.aiassistant.common.exception;

import lombok.Getter;
import org.grayray.aiassistant.common.result.ResultCode;


/**
 *
 *  // 方式1：只给提示，code 默认 500
 *   throw new BusinessException("用户名已存在");
 *   // 方式2：用 ResultCode 枚举
 *   throw new BusinessException(ResultCode.NOT_FOUND, "用户不存在");
 *   // 方式3：静态方法
 *   throw BusinessException.of(ResultCode.UNAUTHORIZED);
 */

/**
 * 业务异常
 * <p>
 * 用于业务逻辑中主动抛出的、有明确提示信息的异常。
 * 由 {@code GlobalExceptionHandler} 统一捕获并转换为 Result 返回。
 */
@Getter
public class BusinessException extends RuntimeException {

    /**
     * 错误码，对应 ResultCode.code
     */
    private final Integer code;

    public BusinessException(String message) {
        super(message);
        this.code = ResultCode.INTERNAL_ERROR.getCode();
    }

    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
    }

    public BusinessException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.code = resultCode.getCode();
    }

    public BusinessException(ResultCode resultCode, String message) {
        super(message);
        this.code = resultCode.getCode();
    }

    /**
     * 便捷构造：只给消息，code 默认为 500
     */
    public static BusinessException of(String message) {
        return new BusinessException(message);
    }

    /**
     * 便捷构造：用 ResultCode 枚举
     */
    public static BusinessException of(ResultCode resultCode) {
        return new BusinessException(resultCode);
    }

    public static BusinessException of(ResultCode resultCode, String message) {
        return new BusinessException(resultCode, message);
    }
}