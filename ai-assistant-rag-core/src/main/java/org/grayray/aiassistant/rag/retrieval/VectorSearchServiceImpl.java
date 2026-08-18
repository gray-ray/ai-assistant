package org.grayray.aiassistant.rag.retrieval;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.grayray.aiassistant.rag.retrieval.VectorSearchProperties;
import org.grayray.aiassistant.rag.service.EmbeddingService;
import org.grayray.aiassistant.rag.service.VectorStoreService;
import org.grayray.aiassistant.rag.retrieval.filter.MetadataFilter;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 向量检索服务默认实现
 * <p>
 * 完整实现"批量 embedding → 单查询 TopK → 元数据过滤 → 多查询合并去重 → 排序 → TopN 截断"
 * 的检索链路，对外暴露 {@link #search(VectorSearchRequest)} 入口。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VectorSearchServiceImpl implements VectorSearchService {

    private final EmbeddingService embeddingService;
    private final VectorStoreService vectorStoreService;
    private final VectorSearchProperties props;

    /** 预期向量维度（bge-m3=1024），维度异常的向量跳过 */
    private static final int EXPECTED_DIMENSION = 1024;

    @Override
    public VectorSearchResult search(VectorSearchRequest request) {
        long start = System.currentTimeMillis();

        // 1. 参数校验 & 取默认值
        if (request == null || CollectionUtils.isEmpty(request.getQueries())) {
            log.warn("[VectorSearch] 请求为空或 queries 为空，返回空结果");
            return emptyResult(0, 0);
        }

        List<String> queries = request.getQueries().stream()
                .filter(Objects::nonNull)
                .filter(s -> !s.isBlank())
                .toList();
        if (queries.isEmpty()) {
            log.warn("[VectorSearch] queries 全部为空，返回空结果");
            return emptyResult(0, 0);
        }

        int topK = firstNonNull(request.getTopKPerQuery(), props.getTopKPerQuery());
        int topN = firstNonNull(request.getFinalTopN(), props.getFinalTopN());
        double minScore = firstNonNull(request.getMinScore(), props.getMinScore());
        boolean enableFilter = props.isEnableMetadataFilter();

        // 2. 批量 embedding
        List<List<Float>> embeddings;
        try {
            embeddings = embeddingService.embedBatch(queries);
        } catch (Exception e) {
            log.error("[VectorSearch] 批量 embedding 异常，返回空结果, queryCount={}", queries.size(), e);
            return emptyResult(queries.size(), System.currentTimeMillis() - start);
        }

        if (CollectionUtils.isEmpty(embeddings)) {
            log.error("[VectorSearch] embedding 返回空，返回空结果");
            return emptyResult(queries.size(), System.currentTimeMillis() - start);
        }

        // 3. 单查询 TopK 检索 + 合并去重
        // key=chunkId, value=RetrievedChunk（同 chunkId 取最高分）
        Map<String, RetrievedChunk> merged = new LinkedHashMap<>();

        int validEmbeddingCount = 0;
        for (int i = 0; i < Math.min(embeddings.size(), queries.size()); i++) {
            List<Float> vec = embeddings.get(i);
            String q = queries.get(i);

            if (vec == null || vec.isEmpty()) {
                log.warn("[VectorSearch] 第 {} 条 query embedding 为空，跳过, query={}", i, truncate(q));
                continue;
            }

            if (vec.size() != EXPECTED_DIMENSION) {
                log.warn("[VectorSearch] 第 {} 条 query 向量维度异常，期望={}, 实际={}, 跳过, query={}",
                        i, EXPECTED_DIMENSION, vec.size(), truncate(q));
                continue;
            }

            validEmbeddingCount++;

            List<RetrievedChunk> hits;
            try {
                hits = vectorStoreService.similaritySearch(vec, topK, minScore);
            } catch (Exception e) {
                log.warn("[VectorSearch] 单条 query 检索异常，跳过, query={}, error={}",
                        truncate(q), e.getMessage());
                continue;
            }

            if (CollectionUtils.isEmpty(hits)) {
                continue;
            }

            // 应用元数据过滤（如 documentIds 限定）
            hits = MetadataFilter.apply(hits, request, enableFilter);

            // 合并去重：同 chunkId 取最高分，记录 hitCount
            for (RetrievedChunk hit : hits) {
                if (hit == null || hit.getChunkId() == null) {
                    continue;
                }
                RetrievedChunk existing = merged.get(hit.getChunkId());
                if (existing == null) {
                    hit.setMatchedQuery(q);
                    hit.setHitCount(1);
                    merged.put(hit.getChunkId(), hit);
                } else {
                    existing.setHitCount(existing.getHitCount() + 1);
                    if (hit.getScore() > existing.getScore()) {
                        existing.setScore(hit.getScore());
                        existing.setMatchedQuery(q);
                    }
                }
            }
        }

        // 4. 按 score 降序排序
        List<RetrievedChunk> sorted = new ArrayList<>(merged.values());
        sorted.sort(Comparator.comparingDouble(RetrievedChunk::getScore).reversed());

        int totalHitCount = sorted.size();

        // 5. TopN 截断
        List<RetrievedChunk> resultList;
        if (sorted.size() > topN) {
            resultList = sorted.subList(0, topN);
        } else {
            resultList = sorted;
        }

        long costMs = System.currentTimeMillis() - start;

        // 6. INFO 日志
        double topScore = resultList.isEmpty() ? 0.0 : resultList.get(0).getScore();
        double bottomScore = resultList.isEmpty() ? 0.0 : resultList.get(resultList.size() - 1).getScore();
        log.info("[VectorSearch] queryCount={}, validEmbeddings={}, topKPerQuery={}, totalHits={}, finalReturned={}, " +
                        "minScore={}, costMs={}ms, topScore={}, bottomScore={}",
                queries.size(), validEmbeddingCount, topK, totalHitCount, resultList.size(),
                minScore, costMs,
                resultList.isEmpty() ? "N/A" : String.format("%.4f", topScore),
                resultList.isEmpty() ? "N/A" : String.format("%.4f", bottomScore));

        // 全低分场景返回空
        if (resultList.isEmpty()) {
            log.info("[VectorSearch] 召回结果为空（所有片段相似度均低于 minScore 或无 embedding 成功），返回空结果");
        }

        return VectorSearchResult.builder()
                .chunks(resultList)
                .totalHitCount(totalHitCount)
                .queryCount(queries.size())
                .costMs(costMs)
                .build();
    }

    // ==================== 工具方法 ====================

    private VectorSearchResult emptyResult(int queryCount, long costMs) {
        return VectorSearchResult.builder()
                .chunks(Collections.emptyList())
                .totalHitCount(0)
                .queryCount(queryCount)
                .costMs(costMs)
                .build();
    }

    private static <T> T firstNonNull(T first, T second) {
        return first != null ? first : second;
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 50 ? s.substring(0, 50) + "..." : s;
    }
}
