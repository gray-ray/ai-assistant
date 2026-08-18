package org.grayray.aiassistant.rag.service.impl;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.grayray.aiassistant.rag.model.EmbeddedChunk;
import org.grayray.aiassistant.rag.model.TextChunk;
import org.grayray.aiassistant.rag.service.EmbeddingService;
import org.grayray.aiassistant.rag.service.VectorStoreService;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 文本向量化服务实现
 * <p>
 * 基于 Spring AI 的 {@link EmbeddingModel} 抽象，默认对接 Ollama 本地 embedding 模型
 * （nomic-embed-text / m3e / bge-m3 等），也可无缝切换到 OpenAI 兼容接口、智谱、通义等。
 * <p>
 * 主要职责：
 * 1. 将 {@link TextChunk} 转为 Spring AI {@link Document}（携带 metadata）
 * 2. 分批调用 Embedding Model（避免超过 provider 单批上限）
 * 3. 组装为 {@link EmbeddedChunk} 并交给 {@link VectorStoreService} 存储
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingServiceImpl implements EmbeddingService {

    /**
     * 单次请求最大 chunk 数。
     * Ollama 本地模型建议 16~64，视模型显存调整。
     */
    @Value("${ai.embedding.batch-size:16}")
    private int batchSize;

    private final EmbeddingModel embeddingModel;
    private final VectorStoreService vectorStoreService;

    @PostConstruct
    public void logConfig() {
        String modelClass = embeddingModel.getClass().getSimpleName();
        int dim;
        try {
            dim = embeddingModel.dimensions();
        } catch (Exception e) {
            dim = -1;
        }
        log.info("");
        log.info("============================================================");
        log.info("🧠 EmbeddingService 已启用");
        log.info("   实现类      : {}", modelClass);
        log.info("   向量维度    : {}（bge-m3=1024，如显示 -1/0 请检查模型是否正常加载）", dim);
        log.info("   批大小      : {}", batchSize);
        log.info("   向量存储    : {}", vectorStoreService.getClass().getSimpleName());
        log.info("   ⚠️  若 Ollama 未启动或 bge-m3 模型未拉取，首次向量化调用将报错");
        log.info("============================================================");
        log.info("");
    }

    @Override
    public List<Float> embed(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        float[] vector = embeddingModel.embed(text);
        return toFloatList(vector);
    }

    @Override
    public List<EmbeddedChunk> embedChunks(List<TextChunk> chunks) {
        if (CollectionUtils.isEmpty(chunks)) {
            return List.of();
        }

        String modelName = getModelName();
        List<EmbeddedChunk> result = new ArrayList<>(chunks.size());

        // 分批处理
        List<List<TextChunk>> batches = partition(chunks, batchSize);
        log.info("开始向量化, 总chunks={}, 每批={}, 批次数={}, model={}",
                chunks.size(), batchSize, batches.size(), modelName);

        long startTime = System.currentTimeMillis();
        AtomicInteger completed = new AtomicInteger(0);

        for (int batchIdx = 0; batchIdx < batches.size(); batchIdx++) {
            List<TextChunk> batch = batches.get(batchIdx);

            // 1) 提取本批次所有 chunk 的文本内容
            List<String> texts = batch.stream()
                    .map(TextChunk::getContent)
                    .collect(Collectors.toList());

            // 2) 调用 embedding 模型（Spring AI 1.1.x API：embed(List<String>)）
            List<float[]> vectors;
            try {
                vectors = embeddingModel.embed(texts);
            } catch (Exception e) {
                log.error("批量 chunk embedding 调用失败, batchIdx={}, batchSize={}, 尝试单条兜底",
                        batchIdx, batch.size(), e);
                vectors = embedChunkTextsOneByOne(texts);
            }

            if (vectors.size() != batch.size()) {
                log.warn("embedding 返回数量与请求不匹配, batchIdx={}, requested={}, got={}",
                        batchIdx, batch.size(), vectors.size());
            }

            // 3) 组装 EmbeddedChunk
            int n = Math.min(batch.size(), vectors.size());
            for (int i = 0; i < n; i++) {
                TextChunk chunk = batch.get(i);
                List<Float> vector = toFloatList(vectors.get(i));
                if (CollectionUtils.isEmpty(vector)) {
                    log.warn("chunk embedding 为空，跳过保存, batchIdx={}, chunkIndex={}, chunkId={}",
                            batchIdx, chunk.getChunkIndex(), chunk.getChunkId());
                    continue;
                }
                result.add(EmbeddedChunk.builder()
                        .chunk(chunk)
                        .embedding(vector)
                        .dimension(vector.size())
                        .embeddingModel(modelName)
                        .build());
            }

            int done = completed.addAndGet(batch.size());
            if ((batchIdx + 1) % 5 == 0 || batchIdx == batches.size() - 1) {
                log.info("向量化进度 {}/{} (batch {}/{})",
                        done, chunks.size(), batchIdx + 1, batches.size());
            }
        }

        long cost = System.currentTimeMillis() - startTime;
        log.info("向量化完成, 总数={}, 耗时={}ms, 平均={}ms/chunk",
                result.size(), cost, result.size() > 0 ? cost / result.size() : 0);

        return result;
    }

    @Override
    public List<List<Float>> embedBatch(List<String> texts) {
        if (CollectionUtils.isEmpty(texts)) {
            return List.of();
        }

        List<List<Float>> result = new ArrayList<>(texts.size());

        try {
            // 1) 先尝试批量调用（一次请求，减少开销）
            List<float[]> vectors = embeddingModel.embed(texts);

            if (vectors.size() != texts.size()) {
                log.warn("embedBatch 返回数量与请求不匹配, requested={}, got={}", texts.size(), vectors.size());
            }

            int n = Math.min(texts.size(), vectors.size());
            for (int i = 0; i < n; i++) {
                float[] vec = vectors.get(i);
                if (vec == null || vec.length == 0) {
                    log.warn("第 {} 条 query embedding 为空，跳过", i);
                    result.add(List.of());
                } else {
                    result.add(toFloatList(vec));
                }
            }
            // 补齐缺失的位置
            while (result.size() < texts.size()) {
                result.add(List.of());
            }
        } catch (Exception e) {
            log.error("批量 embedding 调用失败, 尝试单条兜底, texts={}", texts.size(), e);
            // 批量失败时单条兜底，失败的对应空列表
            for (String text : texts) {
                try {
                    List<Float> vec = embed(text);
                    result.add(vec);
                } catch (Exception ex) {
                    log.warn("单条 query embedding 失败，跳过: {}", ex.getMessage());
                    result.add(List.of());
                }
            }
        }

        return result;
    }

    @Override
    public List<EmbeddedChunk> embedAndStore(List<TextChunk> chunks) {
        List<EmbeddedChunk> embedded = embedChunks(chunks);
        if (!CollectionUtils.isEmpty(embedded)) {
            vectorStoreService.saveAll(embedded);
        }
        return embedded;
    }

    // ==================== 私有方法 ====================

    /**
     * 将 TextChunk 的 metadata 注入到 Spring AI Document（方便后续向量库过滤查询）
     */
    @SuppressWarnings("unused")
    private Document toDocument(TextChunk chunk) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("chunkId", chunk.getChunkId());
        metadata.put("knowledgeId", chunk.getKnowledgeId());
        metadata.put("chunkVersion", chunk.getChunkVersion());
        metadata.put("chunkIndex", chunk.getChunkIndex());
        metadata.put("totalChunks", chunk.getTotalChunks());
        metadata.put("chapterTitle", chunk.getChapterTitle());
        metadata.put("chapterIndex", chunk.getChapterIndex());
        metadata.put("documentId", chunk.getDocumentId());
        metadata.put("pageNumber", chunk.getPageNumber());
        metadata.put("documentName", chunk.getDocumentName());
        metadata.put("tokenCount", chunk.getTokenCount());
        return new Document(chunk.getContent(), metadata);
    }

    /**
     * 获取当前 embedding 模型名称
     */
    private String getModelName() {
        try {
            return embeddingModel.getClass().getSimpleName();
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * float[] → List<Float>
     */
    private static List<Float> toFloatList(float[] arr) {
        if (arr == null || arr.length == 0) {
            return List.of();
        }
        List<Float> list = new ArrayList<>(arr.length);
        for (float v : arr) {
            list.add(v);
        }
        return list;
    }

    /**
     * 按固定大小切分列表
     */
    private static <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> partitions = new ArrayList<>();
        int partitionSize = Math.max(1, size);
        for (int i = 0; i < list.size(); i += partitionSize) {
            partitions.add(list.subList(i, Math.min(i + partitionSize, list.size())));
        }
        return partitions;
    }

    private List<float[]> embedChunkTextsOneByOne(List<String> texts) {
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            try {
                vectors.add(embeddingModel.embed(texts.get(i)));
            } catch (Exception ex) {
                log.warn("单条 chunk embedding 失败, index={}, err={}", i, ex.getMessage());
                vectors.add(new float[0]);
            }
        }
        return vectors;
    }
}
