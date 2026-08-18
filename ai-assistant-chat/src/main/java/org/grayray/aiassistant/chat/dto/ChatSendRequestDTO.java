package org.grayray.aiassistant.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "发送消息请求（同步）")
public class ChatSendRequestDTO {

    @NotBlank(message = "会话ID不能为空")
    @Schema(description = "业务会话ID")
    private String sessionId;

    @NotNull(message = "用户ID不能为空")
    @Schema(description = "用户ID")
    private Long userId;

    @NotBlank(message = "消息内容不能为空")
    @Size(max = 10000, message = "消息内容不能超过10000字")
    @Schema(description = "消息内容")
    private String content;

    @Schema(description = "系统提示词（可选）")
    private String systemPrompt;
}
