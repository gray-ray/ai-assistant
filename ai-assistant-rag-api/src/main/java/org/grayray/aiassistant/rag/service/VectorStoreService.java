package org.grayray.aiassistant.rag.service;

import org.grayray.aiassistant.rag.model.EmbeddedChunk;
import org.grayray.aiassistant.rag.retrieval.RetrievedChunk;

import java.util.List;

/**
 * 向量存储服务接口
 * <p>
 * 抽象向量库的存储/查询操作，具体实现可对接 Milvus / PGVector / Redis / Elasticsearch 等。
 * 当前默认实现基于 Spring AI SimpleVectorStore（内存 + JSON 持久化）。
 */
public interface VectorStoreService {

    /**
     * 批量保存已向量化的切片到向量库
     *
     * @param embeddedChunks 已向量化的 chunk 列表
     */
    void saveAll(List<EmbeddedChunk> embeddedChunks);

    /**
     * 删除指定文档下的所有向量
     *
     * @param documentId 文档 ID
     */
    void deleteByDocumentId(Long documentId);

    /**
     * 相似度检索（单向量）
     * <p>
     * 根据给定的查询向量在向量库中做余弦相似度检索，返回 TopK 个相似度不低于
     * {@code minScore} 的片段，结果按相似度降序排列。
     *
     * @param embedding 查询向量
     * @param topK      召回数量
     * @param minScore  最低相似度阈值（含）
     * @return 命中的片段列表，按相似度降序
     */
    List<RetrievedChunk> similaritySearch(List<Float> embedding, int topK, double minScore);
}
