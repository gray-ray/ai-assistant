package org.grayray.aiassistant.chat.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * SSE 流式聊天事件封装
 * 一次流式对话中会产生多个事件：1个 start、N个 message、1个 done 或 1个 error
 */
@Data
@Builder
@Schema(description = "SSE 流式聊天事件")
public class ChatStreamEvent {

    @Schema(description = "事件类型: start / message / done / error")
    private String event;

    // ===== 通用 =====
    @Schema(description = "会话业务ID")
    private String sessionId;

    // ===== start / done 事件 =====
    @Schema(description = "消息ID（用户消息ID for start，AI消息ID for done）")
    private Long messageId;

    @Schema(description = "消息在会话内的序号")
    private Integer index;

    // ===== message 事件 =====
    @Schema(description = "增量文本片段")
    private String content;

    // ===== done 事件 =====
    @Schema(description = "完整AI回复内容")
    private String fullContent;

    @Schema(description = "结束原因 stop/length")
    private String finishReason;

    @Schema(description = "模型名称")
    private String modelName;

    @Schema(description = "引用来源列表（RAG 检索命中的文档片段）")
    private List<CitationVO> citations;

    // ===== error 事件 =====
    @Schema(description = "错误码")
    private Integer code;

    @Schema(description = "错误信息")
    private String message;

    // ============ 静态工厂方法 ============

    public static ChatStreamEvent start(String sessionId, Long userMsgId, Integer index) {
        return ChatStreamEvent.builder()
                .event("start")
                .sessionId(sessionId)
                .messageId(userMsgId)
                .index(index)
                .build();
    }

    public static ChatStreamEvent message(String content, Integer index) {
        return ChatStreamEvent.builder()
                .event("message")
                .content(content)
                .index(index)
                .build();
    }

    public static ChatStreamEvent done(String sessionId, Long aiMsgId, String fullContent,
                                       String finishReason, String modelName, Integer index) {
        return done(sessionId, aiMsgId, fullContent, finishReason, modelName, index, null);
    }

    public static ChatStreamEvent done(String sessionId, Long aiMsgId, String fullContent,
                                       String finishReason, String modelName, Integer index,
                                       List<CitationVO> citations) {
        return ChatStreamEvent.builder()
                .event("done")
                .sessionId(sessionId)
                .messageId(aiMsgId)
                .fullContent(fullContent)
                .finishReason(finishReason)
                .modelName(modelName)
                .index(index)
                .citations(citations)
                .build();
    }

    public static ChatStreamEvent error(Integer code, String message) {
        return ChatStreamEvent.builder()
                .event("error")
                .code(code)
                .message(message)
                .build();
    }
}
