package org.grayray.aiassistant.chat.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Schema(description = "消息视图对象")
public class ChatMessageVO {

    @Schema(description = "消息ID")
    private Long messageId;

    @Schema(description = "业务会话ID")
    private String sessionId;

    @Schema(description = "角色 user/assistant/system")
    private String role;

    @Schema(description = "消息内容")
    private String content;

    @Schema(description = "模型名称")
    private String modelName;

    @Schema(description = "结束原因")
    private String finishReason;

    @Schema(description = "消息序号")
    private Integer messageIndex;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "引用来源列表（RAG 检索命中的文档片段，仅 assistant 消息有值）")
    private List<CitationVO> citations;
}
