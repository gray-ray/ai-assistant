package org.grayray.aiassistant.common.result;


import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "统一返回结构")
public class Result<T> {

    @Schema(description = "状态吗")
    private Integer code;

    @Schema(description = "提示")
    private String message;


    @Schema(description = "业务数据")
    private T data;

    private Result(){}

    public  static <T> Result<T> success(T data){
        Result<T> r = new Result<>();
        r.setCode(ResultCode.SUCCESS.getCode());
        r.setMessage(ResultCode.SUCCESS.getMessage());
        r.setData(data);
        return  r;
    }

    public static <T> Result<T> success() {
        return success(null);
    }


    public static <T> Result<T> fail(Integer code, String message) {
        Result<T> r = new Result<>();
        r.setCode(code);
        r.setMessage(message);
        return r;
    }

    public static <T> Result<T> fail(ResultCode rc) {
        return fail(rc.getCode(), rc.getMessage());
    }

    public static <T> Result<T> fail(ResultCode rc, String message) {
        return fail(rc.getCode(), message);
    }



}
