package org.grayray.aiassistant.document.service;

import org.grayray.aiassistant.rag.model.TextChunk;

import java.util.List;

/**
 * 文本切片服务
 * 分层切分规则：章节 → 段落 → Token 长度 → Overlap → Metadata
 */
public interface TextChunkService {

    /**
     * 将清洗后的文本切成带元数据的 chunk 列表
     *
     * @param cleanedText  清洗后的文本
     * @param documentId   文档 ID
     * @param documentName 文档名称
     * @return 切片列表
     */
    List<TextChunk> chunk(String cleanedText, Long documentId, String documentName);

    /**
     * 将清洗后的文本切成带知识库元数据的 chunk 列表
     *
     * @param cleanedText  清洗后的文本
     * @param knowledgeId  知识库 ID
     * @param documentId   文档 ID
     * @param documentName 文档名称
     * @return 切片列表
     */
    List<TextChunk> chunk(String cleanedText, Long knowledgeId, Long documentId, String documentName);

    /**
     * 将清洗后的文本切成指定版本的 chunk 列表。
     *
     * @param cleanedText  清洗后的文本
     * @param knowledgeId  知识库 ID
     * @param documentId   文档 ID
     * @param documentName 文档名称
     * @param chunkVersion 切分版本
     * @return 切片列表
     */
    List<TextChunk> chunk(String cleanedText, Long knowledgeId, Long documentId,
                          String documentName, Integer chunkVersion);
}
