package org.grayray.aiassistant.rag.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 跨模块传递的轻量会话消息。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "会话历史消息")
public class ConversationMessage {

    @Schema(description = "消息角色：user / assistant / system")
    private String role;

    @Schema(description = "消息内容")
    private String content;
}
