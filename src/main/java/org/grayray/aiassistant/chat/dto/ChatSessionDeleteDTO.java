package org.grayray.aiassistant.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "删除会话请求")
public class ChatSessionDeleteDTO {

    @NotBlank(message = "会话ID不能为空")
    @Schema(description = "业务会话ID")
    private String sessionId;
}
