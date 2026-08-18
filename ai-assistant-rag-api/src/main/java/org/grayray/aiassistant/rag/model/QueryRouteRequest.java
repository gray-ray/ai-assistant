package org.grayray.aiassistant.rag.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Query Router 输入请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Query Router 请求")
public class QueryRouteRequest {

    @Schema(description = "chat_session 数据库主键 ID")
    private Long sessionId;

    @Schema(description = "用户 ID")
    private Long userId;

    @Schema(description = "用户当前输入")
    private String originalQuery;

    @Schema(description = "历史消息，不包含当前刚插入的用户消息")
    private List<ConversationMessage> history;
}
