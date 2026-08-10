package org.grayray.aiassistant.chat.model;

import lombok.Builder;
import lombok.Data;
import org.grayray.aiassistant.chat.entity.ChatMessage;
import org.grayray.aiassistant.chat.vo.CitationVO;

import java.util.List;

/**
 * 同步发送消息的结果
 * <p>
 * 封装 AI 回复消息 + 引用来源列表，供 Controller 层转换为 VO 返回前端。
 */
@Data
@Builder
public class ChatSendResult {

    /** AI 回复消息实体 */
    private ChatMessage aiMessage;

    /** 引用来源列表（RAG 检索命中的文档片段，可能为空） */
    private List<CitationVO> citations;
}
