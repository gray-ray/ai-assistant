package org.grayray.aiassistant.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "重命名会话请求")
public class ChatSessionRenameDTO {

    @NotBlank(message = "会话ID不能为空")
    @Schema(description = "业务会话ID")
    private String sessionId;

    @NotBlank(message = "标题不能为空")
    @Schema(description = "新标题")
    private String title;
}
