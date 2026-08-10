package org.grayray.aiassistant.rag.rerank;

import org.grayray.aiassistant.rag.retrieval.RetrievedChunk;

import java.util.List;

/**
 * 重排服务（Rerank）
 * <p>
 * 对向量检索召回的 TopK 候选片段做更精细的相关性重排序，
 * 把真正最相关的片段排在前面，提升下游答案生成的精准度。
 * <p>
 * 上游：{@code VectorSearchService}（产出 {@code List<RetrievedChunk>}）
 * 下游：答案生成 / Prompt 组装 / 流式回答
 */
public interface RerankService {

    /**
     * 对候选片段进行重排序
     *
     * @param query    原始查询（用户真实问题，非扩展子问题）
     * @param chunks   待重排的候选片段列表
     * @param topM     返回前 M 条，null 则使用配置默认值
     * @param minScore 最低 rerank 分数阈值（0~1，含），null 则使用配置默认值
     * @return 重排后的结果（按 rerank 分数降序，已截断、已过滤）
     */
    RerankResult rerank(String query, List<RetrievedChunk> chunks,
                        Integer topM, Double minScore);

    /**
     * 便捷方法：使用默认 topM 和 minScore
     */
    default RerankResult rerank(String query, List<RetrievedChunk> chunks) {
        return rerank(query, chunks, null, null);
    }

    /**
     * Rerank 服务是否可用（模型/服务是否就绪）
     *
     * @return true 表示可进行实际重排；false 表示透传
     */
    boolean isAvailable();
}
