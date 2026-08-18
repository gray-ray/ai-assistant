package org.grayray.aiassistant.document.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.grayray.aiassistant.document.entity.KnowledgeBase;

import java.util.List;

public interface KnowledgeBaseService extends IService<KnowledgeBase> {

    KnowledgeBase create(Long userId, String name, String description);

    List<KnowledgeBase> listByUserId(Long userId);

    KnowledgeBase getUserKnowledgeBase(Long userId, Long knowledgeId);

    KnowledgeBase requireActiveUserKnowledgeBase(Long userId, Long knowledgeId);

    KnowledgeBase updateInfo(Long userId, Long knowledgeId, String name, String description, String status);

    void deleteByUser(Long userId, Long knowledgeId);
}
