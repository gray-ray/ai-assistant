package org.grayray.aiassistant.rag.retrieval.filter;

import org.grayray.aiassistant.rag.retrieval.RetrievedChunk;
import org.grayray.aiassistant.rag.retrieval.VectorSearchRequest;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 元数据过滤器
 * <p>
 * 在向量相似度召回之后、合并去重之前应用，用于限定检索范围。
 * 当前支持按 {@code knowledgeId} 与 {@code documentIds} 过滤，后续可扩展章节、文档类型等维度。
 * <p>
 * 对于 SimpleVectorStore 这类内存型向量库：先扩大 K 召回（如 K*2），
 * 再在本过滤器中按 metadata 做后过滤。
 * 对 Milvus / PGVector 等专业向量库：过滤条件可下推到查询引擎侧执行。
 */
public final class MetadataFilter {

    private MetadataFilter() {
    }

    /**
     * 对召回结果应用元数据过滤
     *
     * @param chunks  召回结果
     * @param request 检索请求（携带过滤条件）
     * @param enabled 是否启用过滤（false 时直接返回原列表）
     * @return 过滤后的结果
     */
    public static List<RetrievedChunk> apply(List<RetrievedChunk> chunks,
                                             VectorSearchRequest request,
                                             boolean enabled) {
        if (chunks == null || chunks.isEmpty()) {
            return Collections.emptyList();
        }
        if (!enabled || request == null) {
            return chunks;
        }

        Long knowledgeId = request.getKnowledgeId();
        List<Long> docIds = request.getDocumentIds();
        if (knowledgeId == null && (docIds == null || docIds.isEmpty())) {
            return chunks;
        }

        Set<Long> allowedDocIds = docIds == null ? Collections.emptySet() : docIds.stream()
                .filter(id -> id != null)
                .collect(Collectors.toSet());

        return chunks.stream()
                .filter(c -> knowledgeId == null || knowledgeId.equals(c.getKnowledgeId()))
                .filter(c -> allowedDocIds.isEmpty()
                        || (c.getDocumentId() != null && allowedDocIds.contains(c.getDocumentId())))
                .collect(Collectors.toList());
    }
}
