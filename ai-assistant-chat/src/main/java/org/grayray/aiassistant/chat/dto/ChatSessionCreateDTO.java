package org.grayray.aiassistant.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "创建会话请求")
public class ChatSessionCreateDTO {

    @NotNull(message = "用户ID不能为空")
    @Schema(description = "用户ID")
    private Long userId;

    @Schema(description = "会话标题（默认'新会话'）")
    private String title;

    @Schema(description = "会话类型（默认'normal'）")
    private String sessionType;

    @Schema(description = "绑定的知识库ID，普通会话可为空")
    private Long knowledgeId;
}
