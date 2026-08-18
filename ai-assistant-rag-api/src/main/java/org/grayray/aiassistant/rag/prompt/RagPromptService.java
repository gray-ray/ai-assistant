package org.grayray.aiassistant.rag.prompt;

import org.grayray.aiassistant.rag.context.AssembledContext;
import org.grayray.aiassistant.rag.model.ConversationMessage;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * RAG Prompt 构建契约。
 */
public interface RagPromptService {

    List<Message> buildRagMessages(String userSystemPrompt,
                                   AssembledContext context,
                                   String question,
                                   List<ConversationMessage> history);
}
