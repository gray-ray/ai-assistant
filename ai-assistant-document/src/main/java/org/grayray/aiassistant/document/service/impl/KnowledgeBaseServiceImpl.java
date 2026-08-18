package org.grayray.aiassistant.document.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.grayray.aiassistant.common.exception.BusinessException;
import org.grayray.aiassistant.common.result.ResultCode;
import org.grayray.aiassistant.document.entity.DocumentChunk;
import org.grayray.aiassistant.document.entity.DocumentInfo;
import org.grayray.aiassistant.document.entity.KnowledgeBase;
import org.grayray.aiassistant.document.mapper.DocumentChunkMapper;
import org.grayray.aiassistant.document.mapper.DocumentInfoMapper;
import org.grayray.aiassistant.document.mapper.KnowledgeBaseMapper;
import org.grayray.aiassistant.document.service.KnowledgeBaseService;
import org.grayray.aiassistant.rag.service.VectorStoreService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
@RequiredArgsConstructor
public class KnowledgeBaseServiceImpl extends ServiceImpl<KnowledgeBaseMapper, KnowledgeBase>
        implements KnowledgeBaseService {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_INACTIVE = "INACTIVE";
    private static final String VECTOR_STORE_SIMPLE = "SIMPLE";

    @Value("${ai.vector-store.persistence-path:./vector-store}")
    private String vectorStoreBasePath;

    private final DocumentInfoMapper documentInfoMapper;
    private final DocumentChunkMapper documentChunkMapper;

    @Lazy
    private final VectorStoreService vectorStoreService;

    @Override
    public KnowledgeBase create(Long userId, String name, String description) {
        validateUserId(userId);
        String normalizedName = normalizeName(name);
        ensureNameAvailable(userId, normalizedName, null);

        KnowledgeBase kb = new KnowledgeBase();
        kb.setUserId(userId);
        kb.setName(normalizedName);
        kb.setDescription(trimToNull(description));
        kb.setVectorStoreType(VECTOR_STORE_SIMPLE);
        kb.setStatus(STATUS_ACTIVE);
        save(kb);

        kb.setVectorStorePath(vectorStoreBasePath + "/kb_" + kb.getId());
        kb.setVectorCollection("kb_" + kb.getId());
        updateById(kb);
        return kb;
    }

    @Override
    public List<KnowledgeBase> listByUserId(Long userId) {
        validateUserId(userId);
        return list(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getUserId, userId)
                .orderByDesc(KnowledgeBase::getUpdateTime));
    }

    @Override
    public KnowledgeBase getUserKnowledgeBase(Long userId, Long knowledgeId) {
        validateUserId(userId);
        if (knowledgeId == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "知识库ID不能为空");
        }
        KnowledgeBase kb = getOne(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getId, knowledgeId)
                .eq(KnowledgeBase::getUserId, userId));
        if (kb == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "知识库不存在或无权限");
        }
        return kb;
    }

    @Override
    public KnowledgeBase requireActiveUserKnowledgeBase(Long userId, Long knowledgeId) {
        KnowledgeBase kb = getUserKnowledgeBase(userId, knowledgeId);
        if (!STATUS_ACTIVE.equalsIgnoreCase(kb.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "知识库当前不可用");
        }
        return kb;
    }

    @Override
    public KnowledgeBase updateInfo(Long userId, Long knowledgeId, String name, String description, String status) {
        KnowledgeBase kb = getUserKnowledgeBase(userId, knowledgeId);

        if (StringUtils.hasText(name)) {
            String normalizedName = normalizeName(name);
            ensureNameAvailable(userId, normalizedName, knowledgeId);
            kb.setName(normalizedName);
        }
        if (description != null) {
            kb.setDescription(trimToNull(description));
        }
        if (StringUtils.hasText(status)) {
            String normalizedStatus = status.trim().toUpperCase();
            if (!STATUS_ACTIVE.equals(normalizedStatus) && !STATUS_INACTIVE.equals(normalizedStatus)) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "知识库状态只支持 ACTIVE/INACTIVE");
            }
            kb.setStatus(normalizedStatus);
        }
        updateById(kb);
        return kb;
    }

    @Override
    public void deleteByUser(Long userId, Long knowledgeId) {
        KnowledgeBase kb = getUserKnowledgeBase(userId, knowledgeId);

        List<DocumentInfo> documents = documentInfoMapper.selectList(new LambdaQueryWrapper<DocumentInfo>()
                .eq(DocumentInfo::getKnowledgeId, kb.getId())
                .eq(DocumentInfo::getUserId, userId));

        for (DocumentInfo document : documents) {
            vectorStoreService.deleteByDocumentId(document.getId());
        }

        documentChunkMapper.update(null, new LambdaUpdateWrapper<DocumentChunk>()
                .eq(DocumentChunk::getKnowledgeId, kb.getId())
                .set(DocumentChunk::getIsDeleted, 1));
        documentInfoMapper.update(null, new LambdaUpdateWrapper<DocumentInfo>()
                .eq(DocumentInfo::getKnowledgeId, kb.getId())
                .eq(DocumentInfo::getUserId, userId)
                .set(DocumentInfo::getIsDeleted, 1));
        removeById(kb.getId());
    }

    private void ensureNameAvailable(Long userId, String name, Long excludedKnowledgeId) {
        LambdaQueryWrapper<KnowledgeBase> wrapper = new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getUserId, userId)
                .eq(KnowledgeBase::getName, name)
                .in(KnowledgeBase::getStatus, STATUS_ACTIVE, STATUS_INACTIVE);
        if (excludedKnowledgeId != null) {
            wrapper.ne(KnowledgeBase::getId, excludedKnowledgeId);
        }
        if (count(wrapper) > 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "同名知识库已存在");
        }
    }

    private static void validateUserId(Long userId) {
        if (userId == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "用户ID不能为空");
        }
    }

    private static String normalizeName(String name) {
        if (!StringUtils.hasText(name)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "知识库名称不能为空");
        }
        String normalized = name.trim();
        if (normalized.length() > 100) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "知识库名称不能超过100字");
        }
        return normalized;
    }

    private static String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }
}
