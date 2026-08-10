package org.grayray.aiassistant.chat.service;

import org.grayray.aiassistant.chat.dto.ChatSendRequestDTO;
import org.grayray.aiassistant.chat.entity.ChatMessage;
import org.grayray.aiassistant.chat.model.ChatSendResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

public interface ChatService {

    /** 同步发送：返回完整 AI 回复消息及引用来源 */
    ChatSendResult send(ChatSendRequestDTO dto);

    /** 流式发送：返回 SseEmitter，由 MVC 异步写出 */
    SseEmitter sendStream(String sessionId, Long userId, String content, String systemPrompt);

    /** 查询某会话的历史消息（按 message_index 升序） */
    List<ChatMessage> listMessages(String sessionId, Long userId);
}
