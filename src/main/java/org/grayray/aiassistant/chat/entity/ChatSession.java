package org.grayray.aiassistant.chat.entity;

import com.baomidou.mybatisplus.annotation.*;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("chat_session")
@Schema(description = "聊天会话")
public class ChatSession {

    @TableId(type = IdType.AUTO)
    @Schema(description = "主键ID")
    private Long id;

    @Schema(description = "用户ID")
    private Long userId;

    @Schema(description = "业务会话ID（UUID）")
    private String sessionId;

    @Schema(description = "会话标题")
    private String title;

    @Schema(description = "会话类型")
    private String sessionType;

    @Schema(description = "模型名称")
    private String modelName;

    @Schema(description = "上下文摘要")
    private String summary;

    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    @Schema(description = "更新时间")
    private LocalDateTime updateTime;

    @TableLogic
    @Schema(description = "是否删除 0-否 1-是")
    private Integer isDeleted;
}
