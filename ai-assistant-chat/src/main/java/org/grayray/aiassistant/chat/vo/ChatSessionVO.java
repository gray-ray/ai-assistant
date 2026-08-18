package org.grayray.aiassistant.chat.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "会话视图对象")
public class ChatSessionVO {

    @Schema(description = "主键ID")
    private Long id;

    @Schema(description = "业务会话ID")
    private String sessionId;

    @Schema(description = "用户ID")
    private Long userId;

    @Schema(description = "标题")
    private String title;

    @Schema(description = "会话类型")
    private String sessionType;

    @Schema(description = "模型名称")
    private String modelName;

    @Schema(description = "绑定的知识库ID")
    private Long knowledgeId;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
}
