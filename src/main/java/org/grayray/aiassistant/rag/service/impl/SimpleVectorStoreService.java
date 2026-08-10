package org.grayray.aiassistant.rag.service.impl;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.grayray.aiassistant.rag.model.EmbeddedChunk;
import org.grayray.aiassistant.document.model.TextChunk;
import org.grayray.aiassistant.document.entity.DocumentInfo;
import org.grayray.aiassistant.document.mapper.DocumentInfoMapper;
import org.grayray.aiassistant.rag.service.VectorStoreService;
import org.grayray.aiassistant.rag.retrieval.RetrievedChunk;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.SimpleVectorStoreContent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 基于 Spring AI {@link SimpleVectorStore} 的内存向量存储实现（带本地持久化）
 * <p>
 * 特点：
 * <ul>
 *   <li>使用 Spring AI 官方 SimpleVectorStore（底层 ConcurrentHashMap + 余弦相似度）</li>
 *   <li>通过继承 SimpleVectorStore，直接写入预计算的 embedding（不走 EmbeddingModel 二次计算）</li>
 *   <li>支持按 documentId 删除（内部维护 documentId -> chunkId 列表映射）</li>
 *   <li>支持本地文件持久化（按文档粒度，每个文档一个 JSON 文件），启动时自动加载</li>
 *   <li>Metadata 中包含 {@code documentId}，与 {@code document_info} 表主键一一对应</li>
 * </ul>
 */
@Slf4j
@Service
@Primary
public class SimpleVectorStoreService extends SimpleVectorStore implements VectorStoreService {

    @Value("${ai.vector-store.log-sample-vector:false}")
    private boolean logSampleVector;

    /**
     * documentId -> chunkId 列表（用于按文档删除，因为 SimpleVectorStore 只支持按 ID 删除）
     */
    private final Map<Long, CopyOnWriteArrayList<String>> docToChunkIds = new ConcurrentHashMap<>();

    /** 持久化管理器（可选，未配置路径时为 null/不启用） */
    private final VectorStorePersistenceManager persistenceManager;

    /** 文档信息 Mapper，用于启动时校验 documentId 有效性 */
    private final DocumentInfoMapper documentInfoMapper;

    /**
     * 通过 EmbeddingModel 构建 SimpleVectorStore。
     * <p>
     * 注：虽然传入了 EmbeddingModel，但在 saveAll 中直接写入预计算的 embedding，
     * 不会触发 EmbeddingModel 的二次调用。EmbeddingModel 主要用于后续
     * similaritySearch 场景下自动将查询文本转为向量。
     */
    public SimpleVectorStoreService(EmbeddingModel embeddingModel,
                                    VectorStorePersistenceManager persistenceManager,
                                    DocumentInfoMapper documentInfoMapper) {
        super(SimpleVectorStore.builder(embeddingModel));
        this.persistenceManager = persistenceManager;
        this.documentInfoMapper = documentInfoMapper;
    }

    @PostConstruct
    public void init() {
        log.info("");
        log.info("============================================================");
        log.info("🧺 SimpleVectorStoreService 已启用（Spring AI 内置内存向量存储）");
        log.info("   实现类      : {}", this.getClass().getSimpleName());
        log.info("   底层存储    : ConcurrentHashMap（内存）+ 本地文件持久化");
        log.info("   相似度      : 余弦相似度 / Cosine");
        log.info("   持久化      : {}",
                persistenceManager.isPersistenceEnabled() ? "已启用（启动自动加载）" : "未启用（纯内存，重启丢失）");
        log.info("============================================================");
        log.info("");

        // 从磁盘加载已有向量
        loadVectorsFromDisk();
    }

    /**
     * 从持久化目录加载所有向量到内存
     */
    private void loadVectorsFromDisk() {
        if (!persistenceManager.isPersistenceEnabled()) {
            return;
        }

        // 校验 documentId 是否在 document_info 表中存在且未逻辑删除
        VectorStorePersistenceManager.DocumentIdValidator validator = docId -> {
            DocumentInfo doc = documentInfoMapper.selectById(docId);
            return doc != null; // @TableLogic 会自动过滤 is_deleted=1 的记录
        };

        Map<String, SimpleVectorStoreContent> loaded = persistenceManager.loadAll(validator);
        if (CollectionUtils.isEmpty(loaded)) {
            return;
        }

        // 填充到内存 store
        this.store.putAll(loaded);

        // 重建 docToChunkIds 映射
        for (SimpleVectorStoreContent c : loaded.values()) {
            Object docIdObj = c.getMetadata().get("documentId");
            Long docId = null;
            if (docIdObj instanceof Number n) {
                docId = n.longValue();
            } else if (docIdObj instanceof String s) {
                try {
                    docId = Long.parseLong(s);
                } catch (NumberFormatException ignored) {
                }
            }
            if (docId != null) {
                docToChunkIds.computeIfAbsent(docId, k -> new CopyOnWriteArrayList<>())
                        .add(c.getId());
            }
        }

        log.info("[SimpleVectorStore] 启动加载完成: 已加载向量={}, 文档数={}",
                loaded.size(), docToChunkIds.size());
    }

    @Override
    public void saveAll(List<EmbeddedChunk> embeddedChunks) {
        if (CollectionUtils.isEmpty(embeddedChunks)) {
            return;
        }

        List<SimpleVectorStoreContent> contents = new ArrayList<>(embeddedChunks.size());

        for (EmbeddedChunk ec : embeddedChunks) {
            TextChunk chunk = ec.getChunk();
            if (chunk == null || ec.getEmbedding() == null || ec.getEmbedding().isEmpty()) {
                continue;
            }

            // 生成业务唯一 ID（与 Milvus 实现保持一致，便于后续切换）
            String chunkId = String.format("doc_%d_chunk_%d",
                    chunk.getDocumentId(), chunk.getChunkIndex());

            // metadata（documentId 与 document_info 表主键一一对应）
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("chunkId", chunkId);
            metadata.put("documentId", chunk.getDocumentId());
            metadata.put("chunkIndex", chunk.getChunkIndex());
            metadata.put("totalChunks", chunk.getTotalChunks());
            metadata.put("chapterIndex", chunk.getChapterIndex() != null ? chunk.getChapterIndex() : 0);
            metadata.put("chapterTitle", chunk.getChapterTitle() != null ? chunk.getChapterTitle() : "");
            metadata.put("documentName", chunk.getDocumentName() != null ? chunk.getDocumentName() : "");
            metadata.put("tokenCount", chunk.getTokenCount() != null ? chunk.getTokenCount() : 0);

            // List<Float> -> float[]
            float[] embedding = toFloatArray(ec.getEmbedding());

            // 构造 SimpleVectorStoreContent（直接携带预计算的 embedding）
            SimpleVectorStoreContent content = new SimpleVectorStoreContent(
                    chunkId,
                    chunk.getContent() != null ? chunk.getContent() : "",
                    metadata,
                    embedding
            );
            contents.add(content);

            // 维护 documentId -> chunkId 映射
            docToChunkIds.computeIfAbsent(chunk.getDocumentId(), k -> new CopyOnWriteArrayList<>())
                    .add(chunkId);
        }

        if (contents.isEmpty()) {
            return;
        }

        // 直接写入 protected 的 store 字段（继承自 SimpleVectorStore），
        // 绕过 doAdd() 中 EmbeddingModel 的二次 embedding 调用，使用我们预计算好的向量
        for (SimpleVectorStoreContent content : contents) {
            this.store.put(content.getId(), content);
        }

        EmbeddedChunk first = embeddedChunks.get(0);
        log.info("[SimpleVectorStore] 已保存向量, count={}, dimension={}, documentId={}",
                contents.size(),
                first.getEmbedding() != null ? first.getEmbedding().size() : 0,
                first.getChunk() != null ? first.getChunk().getDocumentId() : null);

        if (logSampleVector && first.getEmbedding() != null && !first.getEmbedding().isEmpty()) {
            List<Float> v = first.getEmbedding();
            log.debug("[SimpleVectorStore] 示例向量(前5维)={}",
                    v.subList(0, Math.min(5, v.size())));
        }

        // 持久化到磁盘（按文档分组保存）
        persistAllToDisk(embeddedChunks, contents);
    }

    /**
     * 将向量内容按 documentId 分组后持久化到磁盘
     */
    private void persistAllToDisk(List<EmbeddedChunk> embeddedChunks,
                                  List<SimpleVectorStoreContent> contents) {
        if (!persistenceManager.isPersistenceEnabled()) {
            return;
        }

        // 按 documentId 分组
        Map<Long, List<SimpleVectorStoreContent>> grouped = new HashMap<>();
        for (SimpleVectorStoreContent c : contents) {
            Object docIdObj = c.getMetadata().get("documentId");
            Long docId = null;
            if (docIdObj instanceof Number n) {
                docId = n.longValue();
            }
            if (docId != null) {
                grouped.computeIfAbsent(docId, k -> new ArrayList<>()).add(c);
            }
        }

        for (Map.Entry<Long, List<SimpleVectorStoreContent>> entry : grouped.entrySet()) {
            persistenceManager.saveDocumentVectors(entry.getKey(), entry.getValue());
        }
    }

    @Override
    public void deleteByDocumentId(Long documentId) {
        if (documentId == null) {
            return;
        }

        List<String> chunkIds = docToChunkIds.remove(documentId);
        if (CollectionUtils.isEmpty(chunkIds)) {
            log.info("[SimpleVectorStore] 未找到文档向量，跳过删除, documentId={}", documentId);
            return;
        }

        // 从内存删除
        this.doDelete(chunkIds);

        log.info("[SimpleVectorStore] 已删除文档向量, documentId={}, chunkCount={}",
                documentId, chunkIds.size());

        // 同步删除磁盘文件
        if (persistenceManager.isPersistenceEnabled()) {
            persistenceManager.deleteDocumentVectors(documentId);
        }
    }

    // ==================== 检索能力 ====================

    @Override
    public List<RetrievedChunk> similaritySearch(List<Float> embedding, int topK, double minScore) {
        if (embedding == null || embedding.isEmpty() || topK <= 0) {
            return List.of();
        }

        float[] queryVec = toFloatArray(embedding);
        if (queryVec.length == 0) {
            return List.of();
        }

        if (store.isEmpty()) {
            log.warn("[SimpleVectorStore] 向量库为空，检索返回空结果");
            return List.of();
        }

        // 用小顶堆维护 TopK，节省内存（避免全量排序）
        PriorityQueue<RetrievedChunk> topKHeap = new PriorityQueue<>(
                topK, Comparator.comparingDouble(RetrievedChunk::getScore));

        int candidateCount = 0;
        double queryNorm = norm(queryVec);

        if (queryNorm == 0.0) {
            log.warn("[SimpleVectorStore] 查询向量模长为 0，无法计算相似度");
            return List.of();
        }

        for (SimpleVectorStoreContent content : store.values()) {
            float[] docVec = content.getEmbedding();
            if (docVec == null || docVec.length != queryVec.length) {
                continue;
            }
            double score = cosineSimilarity(queryVec, docVec, queryNorm);
            if (score < minScore) {
                continue;
            }
            candidateCount++;

            RetrievedChunk chunk = toRetrievedChunk(content, score);

            if (topKHeap.size() < topK) {
                topKHeap.offer(chunk);
            } else if (score > topKHeap.peek().getScore()) {
                topKHeap.poll();
                topKHeap.offer(chunk);
            }
        }

        // 小顶堆转成按 score 降序的列表
        List<RetrievedChunk> result = new ArrayList<>(topKHeap.size());
        while (!topKHeap.isEmpty()) {
            result.add(0, topKHeap.poll());
        }

        log.debug("[SimpleVectorStore] similaritySearch 完成, candidates={}, returned={}, topScore={}",
                candidateCount, result.size(),
                result.isEmpty() ? "N/A" : String.format("%.4f", result.get(0).getScore()));

        return result;
    }

    /**
     * 将 SimpleVectorStoreContent + score 转换为 RetrievedChunk
     */
    private RetrievedChunk toRetrievedChunk(SimpleVectorStoreContent content, double score) {
        Map<String, Object> meta = content.getMetadata();
        return RetrievedChunk.builder()
                .chunkId(content.getId())
                .documentId(getLongMeta(meta, "documentId"))
                .documentName(getStringMeta(meta, "documentName"))
                .chunkIndex(getIntMeta(meta, "chunkIndex"))
                .totalChunks(getIntMeta(meta, "totalChunks"))
                .chapterIndex(getIntMeta(meta, "chapterIndex"))
                .chapterTitle(getStringMeta(meta, "chapterTitle"))
                .content(content.getText())
                .tokenCount(getIntMeta(meta, "tokenCount"))
                .score(score)
                .build();
    }

    // ==================== 相似度计算 ====================

    /**
     * 余弦相似度计算
     *
     * @param queryVec 查询向量
     * @param docVec   文档向量
     * @param queryNorm 查询向量的模长（预计算，避免重复计算）
     * @return 余弦相似度，范围 [-1, 1]
     */
    private static double cosineSimilarity(float[] queryVec, float[] docVec, double queryNorm) {
        double dotProduct = 0.0;
        double docNorm = 0.0;
        for (int i = 0; i < queryVec.length; i++) {
            float a = queryVec[i];
            float b = docVec[i];
            dotProduct += a * b;
            docNorm += b * b;
        }
        docNorm = Math.sqrt(docNorm);
        if (docNorm == 0.0) {
            return 0.0;
        }
        return dotProduct / (queryNorm * docNorm);
    }

    private static double norm(float[] vec) {
        double sum = 0.0;
        for (float v : vec) {
            sum += v * v;
        }
        return Math.sqrt(sum);
    }

    // ==================== Metadata 工具 ====================

    private static String getStringMeta(Map<String, Object> meta, String key) {
        Object v = meta.get(key);
        return v == null ? "" : v.toString();
    }

    private static Integer getIntMeta(Map<String, Object> meta, String key) {
        Object v = meta.get(key);
        if (v == null) {
            return 0;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(v.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Long getLongMeta(Map<String, Object> meta, String key) {
        Object v = meta.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ==================== 私有方法 ====================

    /**
     * List<Float> -> float[]
     */
    private static float[] toFloatArray(List<Float> list) {
        if (list == null || list.isEmpty()) {
            return new float[0];
        }
        float[] arr = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = list.get(i) != null ? list.get(i) : 0f;
        }
        return arr;
    }
}
