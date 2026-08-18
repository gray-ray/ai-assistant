package org.grayray.aiassistant.rag.retrieval;

import org.grayray.aiassistant.rag.retrieval.VectorSearchProperties;
import org.grayray.aiassistant.rag.service.EmbeddingService;
import org.grayray.aiassistant.rag.service.VectorStoreService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * VectorSearchServiceImpl 单元测试
 * <p>
 * 通过 Mock 掉 {@link EmbeddingService} 和 {@link VectorStoreService}，覆盖：
 * <ul>
 *   <li>单查询/多查询检索</li>
 *   <li>多查询合并去重（同 chunkId 取最高分）</li>
 *   <li>TopN 截断</li>
 *   <li>空 query / embedding 失败 / 空结果降级</li>
 *   <li>元数据过滤（documentIds）</li>
 *   <li>排序正确性</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VectorSearchServiceImplTest {

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private VectorStoreService vectorStoreService;

    private VectorSearchProperties props;

    private VectorSearchServiceImpl vectorSearchService;

    @BeforeEach
    void setUp() {
        props = new VectorSearchProperties();
        props.setTopKPerQuery(4);
        props.setFinalTopN(6);
        props.setMinScore(0.5);
        props.setEnableMetadataFilter(true);

        vectorSearchService = new VectorSearchServiceImpl(embeddingService, vectorStoreService, props);
    }

    // ---- 工具方法：构造指定分数的 RetrievedChunk ----
    private RetrievedChunk chunk(String chunkId, Long docId, double score) {
        return chunk(chunkId, null, docId, score);
    }

    private RetrievedChunk chunk(String chunkId, Long knowledgeId, Long docId, double score) {
        return RetrievedChunk.builder()
                .chunkId(chunkId)
                .knowledgeId(knowledgeId)
                .documentId(docId)
                .documentName("test.pdf")
                .chunkIndex(0)
                .score(score)
                .content("chunk content for " + chunkId)
                .build();
    }

    // ---- 工具方法：构造维度为 1024 的 mock 向量 ----
    private List<Float> mockVector(int seed) {
        List<Float> vec = new ArrayList<>(1024);
        for (int i = 0; i < 1024; i++) {
            vec.add((float) ((seed + i % 7) / 100.0));
        }
        return vec;
    }

    @Test
    @DisplayName("空查询返回空结果，不抛异常")
    void emptyQueries_returnEmpty() {
        VectorSearchResult result = vectorSearchService.search(
                VectorSearchRequest.builder().queries(Collections.emptyList()).build());

        assertNotNull(result);
        assertTrue(result.isEmpty());
        assertEquals(0, result.getTotalHitCount());
        assertEquals(0, result.getQueryCount());
        verifyNoInteractions(vectorStoreService);
    }

    @Test
    @DisplayName("null 请求返回空结果")
    void nullRequest_returnEmpty() {
        VectorSearchResult result = vectorSearchService.search((VectorSearchRequest) null);
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("单查询 TopK 检索成功，返回结果按分数降序")
    void singleQuery_searchSuccess() {
        String query = "什么是向量检索";
        List<Float> vec = mockVector(1);
        when(embeddingService.embedBatch(eq(List.of(query))))
                .thenReturn(List.of(vec));

        List<RetrievedChunk> hits = Arrays.asList(
                chunk("doc_1_chunk_0", 1L, 0.9),
                chunk("doc_1_chunk_1", 1L, 0.7),
                chunk("doc_1_chunk_2", 1L, 0.55)
        );
        when(vectorStoreService.similaritySearch(anyList(), eq(4), eq(0.5)))
                .thenReturn(hits);

        VectorSearchResult result = vectorSearchService.search(
                VectorSearchRequest.of(query));

        assertFalse(result.isEmpty());
        assertEquals(3, result.getChunks().size());
        assertEquals(3, result.getTotalHitCount());
        assertEquals(1, result.getQueryCount());
        // 按分数降序
        assertEquals(0.9, result.getChunks().get(0).getScore(), 0.001);
        assertEquals(0.55, result.getChunks().get(2).getScore(), 0.001);
        assertTrue(result.getCostMs() >= 0);
        assertEquals(query, result.getChunks().get(0).getMatchedQuery());
    }

    @Test
    @DisplayName("多查询合并去重：同 chunkId 被命中时取最高分，并累计 hitCount")
    void multiQuery_mergeDedup_keepHighestScore() {
        List<String> queries = List.of("向量检索", "TopK", "余弦相似度");
        List<Float> vec1 = mockVector(1);
        List<Float> vec2 = mockVector(2);
        List<Float> vec3 = mockVector(3);
        when(embeddingService.embedBatch(queries))
                .thenReturn(List.of(vec1, vec2, vec3));

        // 使用 AtomicInteger 跟踪调用次数
        AtomicInteger callIdx = new AtomicInteger(0);
        when(vectorStoreService.similaritySearch(anyList(), eq(4), eq(0.5)))
                .thenAnswer(inv -> {
                    int idx = callIdx.getAndIncrement();
                    return switch (idx) {
                        case 0 -> Arrays.asList(
                                chunk("doc_1_chunk_a", 1L, 0.9),
                                chunk("doc_1_chunk_b", 1L, 0.7));
                        case 1 -> Arrays.asList(
                                chunk("doc_1_chunk_b", 1L, 0.8),
                                chunk("doc_1_chunk_c", 1L, 0.6));
                        default -> Arrays.asList(
                                chunk("doc_1_chunk_a", 1L, 0.85),
                                chunk("doc_1_chunk_d", 1L, 0.55));
                    };
                });

        VectorSearchResult result = vectorSearchService.search(
                VectorSearchRequest.builder().queries(queries).build());

        // 去重后应只剩 4 个 chunk：a, b, c, d
        assertEquals(4, result.getTotalHitCount());
        // chunk_a 最高分应为 0.9，命中次数 2
        RetrievedChunk chunkA = result.getChunks().stream()
                .filter(c -> "doc_1_chunk_a".equals(c.getChunkId())).findFirst().orElseThrow();
        assertEquals(0.9, chunkA.getScore(), 0.001);
        assertEquals(2, chunkA.getHitCount());
        // chunk_b 最高分应为 0.8（不是 0.7）
        RetrievedChunk chunkB = result.getChunks().stream()
                .filter(c -> "doc_1_chunk_b".equals(c.getChunkId())).findFirst().orElseThrow();
        assertEquals(0.8, chunkB.getScore(), 0.001);
        assertEquals(2, chunkB.getHitCount());
        // 排序：a(0.9) > b(0.8) > c(0.6) > d(0.55)
        assertEquals(0.9, result.getChunks().get(0).getScore(), 0.001);
        assertEquals(0.8, result.getChunks().get(1).getScore(), 0.001);
    }

    @Test
    @DisplayName("TopN 截断：合并后超过 finalTopN 时截断到 N 条")
    void finalTopN_truncation() {
        props.setFinalTopN(2);
        props.setTopKPerQuery(10);
        props.setMinScore(0.0);

        String query = "测试";
        when(embeddingService.embedBatch(eq(List.of(query))))
                .thenReturn(List.of(mockVector(1)));

        List<RetrievedChunk> hits = Arrays.asList(
                chunk("c1", 1L, 0.9),
                chunk("c2", 1L, 0.8),
                chunk("c3", 1L, 0.7),
                chunk("c4", 1L, 0.6),
                chunk("c5", 1L, 0.5)
        );
        when(vectorStoreService.similaritySearch(anyList(), eq(10), eq(0.0)))
                .thenReturn(hits);

        VectorSearchResult result = vectorSearchService.search(VectorSearchRequest.of(query));

        assertEquals(5, result.getTotalHitCount());
        assertEquals(2, result.getChunks().size());
        assertEquals(0.9, result.getChunks().get(0).getScore(), 0.001);
        assertEquals(0.8, result.getChunks().get(1).getScore(), 0.001);
    }

    @Test
    @DisplayName("结果不足 TopN 时返回实际命中数，不填充")
    void fewerThanTopN_returnActual() {
        String query = "测试";
        when(embeddingService.embedBatch(eq(List.of(query))))
                .thenReturn(List.of(mockVector(1)));
        when(vectorStoreService.similaritySearch(anyList(), eq(4), eq(0.5)))
                .thenReturn(List.of(chunk("c1", 1L, 0.9)));

        VectorSearchResult result = vectorSearchService.search(VectorSearchRequest.of(query));

        assertEquals(1, result.getChunks().size());
        assertEquals(1, result.getTotalHitCount());
    }

    @Test
    @DisplayName("embedding 全部失败返回空结果，不抛异常")
    void embeddingAllFail_returnEmpty() {
        String query = "测试";
        when(embeddingService.embedBatch(anyList()))
                .thenThrow(new RuntimeException("Ollama connection refused"));

        VectorSearchResult result = vectorSearchService.search(VectorSearchRequest.of(query));

        assertTrue(result.isEmpty());
        assertEquals(1, result.getQueryCount());
        verify(vectorStoreService, never()).similaritySearch(anyList(), anyInt(), anyDouble());
    }

    @Test
    @DisplayName("单条 query embedding 失败跳过，其他继续")
    void oneEmbeddingFail_otherContinues() {
        List<String> queries = List.of("q1", "q2");
        when(embeddingService.embedBatch(queries))
                .thenReturn(Arrays.asList(Collections.emptyList(), mockVector(2)));

        when(vectorStoreService.similaritySearch(anyList(), eq(4), eq(0.5)))
                .thenReturn(List.of(chunk("c1", 1L, 0.8)));

        VectorSearchResult result = vectorSearchService.search(
                VectorSearchRequest.builder().queries(queries).build());

        assertFalse(result.isEmpty());
        assertEquals(1, result.getChunks().size());
        verify(vectorStoreService, times(1)).similaritySearch(anyList(), anyInt(), anyDouble());
    }

    @Test
    @DisplayName("向量维度异常（非 1024）跳过")
    void wrongDimension_skip() {
        String query = "测试";
        List<Float> badVec = new ArrayList<>(Collections.nCopies(512, 0.1f));
        when(embeddingService.embedBatch(eq(List.of(query))))
                .thenReturn(List.of(badVec));

        VectorSearchResult result = vectorSearchService.search(VectorSearchRequest.of(query));

        assertTrue(result.isEmpty());
        verify(vectorStoreService, never()).similaritySearch(anyList(), anyInt(), anyDouble());
    }

    @Test
    @DisplayName("元数据过滤：限定 documentIds 后只返回指定文档的片段")
    void metadataFilter_byDocumentIds() {
        String query = "测试";
        when(embeddingService.embedBatch(eq(List.of(query))))
                .thenReturn(List.of(mockVector(1)));

        List<RetrievedChunk> hits = Arrays.asList(
                chunk("doc_1_c0", 1L, 0.9),
                chunk("doc_2_c0", 2L, 0.85),
                chunk("doc_1_c1", 1L, 0.7)
        );
        when(vectorStoreService.similaritySearch(anyList(), eq(4), eq(0.5)))
                .thenReturn(hits);

        VectorSearchResult result = vectorSearchService.search(
                VectorSearchRequest.builder()
                        .queries(List.of(query))
                        .documentIds(List.of(1L))
                        .build());

        assertEquals(2, result.getChunks().size());
        assertTrue(result.getChunks().stream().allMatch(c -> Long.valueOf(1L).equals(c.getDocumentId())));
    }

    @Test
    @DisplayName("元数据过滤：限定 knowledgeId 后只返回指定知识库的片段")
    void metadataFilter_byKnowledgeId() {
        String query = "测试";
        when(embeddingService.embedBatch(eq(List.of(query))))
                .thenReturn(List.of(mockVector(1)));

        List<RetrievedChunk> hits = Arrays.asList(
                chunk("kb_10_doc_1_c0", 10L, 1L, 0.9),
                chunk("kb_20_doc_2_c0", 20L, 2L, 0.85),
                chunk("kb_10_doc_3_c0", 10L, 3L, 0.7)
        );
        when(vectorStoreService.similaritySearch(anyList(), eq(4), eq(0.5)))
                .thenReturn(hits);

        VectorSearchResult result = vectorSearchService.search(
                VectorSearchRequest.builder()
                        .queries(List.of(query))
                        .knowledgeId(10L)
                        .build());

        assertEquals(2, result.getChunks().size());
        assertTrue(result.getChunks().stream().allMatch(c -> Long.valueOf(10L).equals(c.getKnowledgeId())));
    }

    @Test
    @DisplayName("所有分数低于 minScore 时返回空结果（similaritySearch 返回空）")
    void allBelowMinScore_returnEmpty() {
        String query = "无关问题";
        when(embeddingService.embedBatch(eq(List.of(query))))
                .thenReturn(List.of(mockVector(1)));
        when(vectorStoreService.similaritySearch(anyList(), eq(4), eq(0.5)))
                .thenReturn(Collections.emptyList());

        VectorSearchResult result = vectorSearchService.search(VectorSearchRequest.of(query));

        assertTrue(result.isEmpty());
        assertEquals(0, result.getTotalHitCount());
    }

    @Test
    @DisplayName("便捷方法 search(String) 使用默认参数")
    void convenienceMethod_singleString() {
        String query = "你好";
        when(embeddingService.embedBatch(eq(List.of(query))))
                .thenReturn(List.of(mockVector(1)));
        when(vectorStoreService.similaritySearch(anyList(), eq(4), eq(0.5)))
                .thenReturn(List.of(chunk("c1", 1L, 0.95)));

        VectorSearchResult result = vectorSearchService.search(query);

        assertFalse(result.isEmpty());
        assertEquals(1, result.getQueryCount());
        assertEquals(1, result.getChunks().size());
    }

    @Test
    @DisplayName("VectorSearchResult.isEmpty 正确反映 chunks 是否为空")
    void isEmpty_logic() {
        VectorSearchResult empty = VectorSearchResult.builder()
                .chunks(Collections.emptyList()).build();
        assertTrue(empty.isEmpty());

        VectorSearchResult nonEmpty = VectorSearchResult.builder()
                .chunks(List.of(chunk("c1", 1L, 0.9))).build();
        assertFalse(nonEmpty.isEmpty());
    }
}
