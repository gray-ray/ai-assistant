package org.grayray.aiassistant.user.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class SysUserIdDTO {
    @NotNull(message = "用户id不能为空")
    private Long id;
}
