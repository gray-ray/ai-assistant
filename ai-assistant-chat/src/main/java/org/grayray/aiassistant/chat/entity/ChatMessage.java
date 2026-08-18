package org.grayray.aiassistant.chat.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@TableName(value = "chat_message", autoResultMap = true)
@Schema(description = "聊天消息")
public class ChatMessage {

    @TableId(type = IdType.AUTO)
    @Schema(description = "消息主键")
    private Long id;

    @Schema(description = "会话数据库主键ID（关联chat_session.id）")
    private Long sessionId;

    @Schema(description = "用户ID")
    private Long userId;

    @Schema(description = "角色 user/assistant/system")
    private String role;

    @Schema(description = "消息内容")
    private String content;

    /**
     * 引用来源列表（仅 assistant 消息有值）
     * <p>
     * 持久化为 chat_message.citations_json 列的 JSON 数组，
     * 通过 MyBatis-Plus JacksonTypeHandler 自动序列化/反序列化。
     */
    @TableField(value = "citations_json", typeHandler = JacksonTypeHandler.class)
    @Schema(description = "引用来源列表 JSON（仅 assistant 消息有值）")
    private List<ChatMessageCitation> citations;

    @Schema(description = "会话内消息序号")
    private Integer messageIndex;

    @Schema(description = "模型名称")
    private String modelName;

    @Schema(description = "结束原因 stop/length")
    private String finishReason;

    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @TableLogic
    @Schema(description = "是否删除 0-否 1-是")
    private Integer isDeleted;
}
