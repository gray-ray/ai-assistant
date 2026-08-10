package org.grayray.aiassistant.rag.retrieval;

import java.util.List;

/**
 * 向量检索服务（对外入口）
 * <p>
 * 负责完整的 TopK 召回链路：
 * <ol>
 *   <li>Query Embedding：批量对 query 列表做向量化</li>
 *   <li>向量相似度检索：每条 query 独立召回 TopK 个候选片段</li>
 *   <li>元数据过滤：可选按 documentId 等维度限定范围</li>
 *   <li>多查询合并去重：同一 chunk 被多条 query 命中时取最高分</li>
 *   <li>排序 & 截断：按相似度降序后截断到 TopN</li>
 * </ol>
 * <p>
 * 上游：Query Router（产出 {@code RoutedQuery.queries}）
 * 下游：Rerank（可选） / Answer Generation / 流式回答
 */
public interface VectorSearchService {

    /**
     * 向量检索入口（支持多查询）
     *
     * @param request 检索请求
     * @return 检索结果（已合并去重、按相似度降序、TopN 截断）
     */
    VectorSearchResult search(VectorSearchRequest request);

    /**
     * 便捷方法：单查询检索（使用所有默认参数）
     *
     * @param query 查询文本
     * @return 检索结果
     */
    default VectorSearchResult search(String query) {
        return search(VectorSearchRequest.builder()
                .queries(List.of(query))
                .build());
    }
}
