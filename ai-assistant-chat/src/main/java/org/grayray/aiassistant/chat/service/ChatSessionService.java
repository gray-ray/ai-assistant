package org.grayray.aiassistant.chat.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.grayray.aiassistant.chat.entity.ChatSession;

public interface ChatSessionService extends IService<ChatSession> {

    /** 创建会话 */
    ChatSession create(Long userId, String title, String sessionType);

    /** 创建绑定知识库的会话 */
    ChatSession create(Long userId, String title, String sessionType, Long knowledgeId);

    /** 查询用户会话列表（按 updateTime 倒序） */
    java.util.List<ChatSession> listByUserId(Long userId);

    /** 按业务 sessionId 查询 */
    ChatSession getBySessionId(String sessionId);

    /** 重命名 */
    void rename(String sessionId, String title);

    /** 逻辑删除 */
    void delete(String sessionId);
}
