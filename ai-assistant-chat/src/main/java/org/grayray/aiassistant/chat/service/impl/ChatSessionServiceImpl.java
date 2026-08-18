package org.grayray.aiassistant.chat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.grayray.aiassistant.common.exception.BusinessException;
import org.grayray.aiassistant.common.result.ResultCode;
import org.grayray.aiassistant.chat.entity.ChatSession;
import org.grayray.aiassistant.chat.mapper.ChatSessionMapper;
import org.grayray.aiassistant.chat.service.ChatSessionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class ChatSessionServiceImpl extends ServiceImpl<ChatSessionMapper, ChatSession> implements ChatSessionService {

    @Value("${spring.ai.deepseek.chat.options.model:deepseek-v4-flash}")
    private String defaultModelName;

    @Override
    public ChatSession create(Long userId, String title, String sessionType) {
        return create(userId, title, sessionType, null);
    }

    @Override
    public ChatSession create(Long userId, String title, String sessionType, Long knowledgeId) {
        ChatSession session = new ChatSession();
        session.setUserId(userId);
        session.setSessionId(UUID.randomUUID().toString());
        session.setTitle(title == null || title.isBlank() ? "新会话" : title);
        session.setSessionType(sessionType == null || sessionType.isBlank() ? "normal" : sessionType);
        session.setModelName(defaultModelName);
        session.setKnowledgeId(knowledgeId);
        baseMapper.insert(session);
        return session;
    }

    @Override
    public List<ChatSession> listByUserId(Long userId) {
        return list(new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getUserId, userId)
                .orderByDesc(ChatSession::getUpdateTime));
    }

    @Override
    public ChatSession getBySessionId(String sessionId) {
        return getOne(new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getSessionId, sessionId));
    }

    @Override
    public void rename(String sessionId, String title) {
        ChatSession session = getBySessionId(sessionId);
        if (session == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "会话不存在");
        }
        session.setTitle(title);
        updateById(session);
    }

    @Override
    public void delete(String sessionId) {
        ChatSession session = getBySessionId(sessionId);
        if (session == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "会话不存在");
        }
        removeById(session.getId());
    }
}
