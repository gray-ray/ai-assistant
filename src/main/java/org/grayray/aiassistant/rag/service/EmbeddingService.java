package org.grayray.aiassistant.rag.service;

import org.grayray.aiassistant.rag.model.EmbeddedChunk;
import org.grayray.aiassistant.document.model.TextChunk;

import java.util.List;

/**
 * 文本向量化服务
 * <p>
 * 将已切分的 {@link TextChunk} 通过 Embedding Model 转为向量，并存储到向量库。
 */
public interface EmbeddingService {

    /**
     * 对单个文本生成 embedding 向量
     *
     * @param text 输入文本
     * @return 向量（浮点数列表）
     */
    List<Float> embed(String text);

    /**
     * 对切片列表批量向量化（内部会分批处理，避免单次请求过大）
     *
     * @param chunks 文本切片列表
     * @return 已向量化的切片列表
     */
    List<EmbeddedChunk> embedChunks(List<TextChunk> chunks);

    /**
     * 对切片列表批量向量化并存储到向量库
     *
     * @param chunks 文本切片列表
     * @return 已向量化的切片列表
     */
    List<EmbeddedChunk> embedAndStore(List<TextChunk> chunks);

    /**
     * 批量对文本列表生成 embedding
     * <p>
     * 用于向量检索场景：将 Query Router 产出的多条 query 一次性向量化，
     * 返回结果与 texts 下标一一对应；单条失败时对应位置返回空列表（不抛异常）。
     *
     * @param texts 文本列表
     * @return 与 texts 下标一一对应的 embedding 列表
     */
    List<List<Float>> embedBatch(List<String> texts);
}
