// ======================================================================
// Milvus 向量存储实现（已注释，改用 Spring AI SimpleVectorStore）
// ----------------------------------------------------------------------
// 如需重新启用 Milvus：
//   1. 取消 pom.xml 中 milvus-sdk-java 依赖的注释
//   2. 取消 MilvusConfig/MilvusProperties 的类注释
//   3. 取消本文件的类注释（以及下方全部代码注释）
//   4. 取消 application.yaml 中 milvus 配置块的注释
//   5. 确保 Milvus 服务已启动（默认 localhost:19530）
// ======================================================================
/*
package org.grayray.aiassistant.rag.service.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.index.request.CreateIndexReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.InsertReq;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.grayray.aiassistant.config.vector.MilvusProperties;
import org.grayray.aiassistant.rag.model.EmbeddedChunk;
import org.grayray.aiassistant.rag.service.VectorStoreService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Milvus 向量存储实现
 * <p>
 * 集合 Schema（动态字段开启，便于后续扩展 metadata）：
 * <pre>
 *   id             (VarChar, 主键, 自动生成的唯一 ID)
 *   chunk_id       (VarChar, 业务主键: doc_{docId}_chunk_{idx})
 *   document_id    (Int64,   文档 ID，用于过滤/删除)
 *   chunk_index    (Int32,   切片序号)
 *   chapter_index  (Int32,   章节序号)
 *   chapter_title  (VarChar, 章节标题)
 *   document_name  (VarChar, 文档名称)
 *   token_count    (Int32,   token 数)
 *   content        (VarChar, 切片文本内容)
 *   embedding      (FloatVector, dim=milvus.dimension)
 * </pre>
 * <p>
 * 说明： Milvus 2.3+ 的主键（primary key）要求是 Int64 或 VarChar，
 * 这里用自增的 VarChar 作为内部主键，额外存 {@code chunk_id} 作为业务唯一标识。
 * 为了简化和性能，使用 Milvus 自增主键即可，按 {@code document_id} 过滤删除。
 *
@Slf4j
@Service
@Primary
@RequiredArgsConstructor
@ConditionalOnProperty(name = "milvus.enabled", havingValue = "true", matchIfMissing = true)
public class MilvusVectorStoreService implements VectorStoreService {

    // ======== 字段名常量 ========
    public static final String FIELD_ID = "id";
    public static final String FIELD_CHUNK_ID = "chunk_id";
    public static final String FIELD_DOCUMENT_ID = "document_id";
    public static final String FIELD_CHUNK_INDEX = "chunk_index";
    public static final String FIELD_CHAPTER_INDEX = "chapter_index";
    public static final String FIELD_CHAPTER_TITLE = "chapter_title";
    public static final String FIELD_DOCUMENT_NAME = "document_name";
    public static final String FIELD_TOKEN_COUNT = "token_count";
    public static final String FIELD_CONTENT = "content";
    public static final String FIELD_EMBEDDING = "embedding";

    /** VarChar 字段最大长度 *
    private static final int VARCHAR_MAX = 65535;

    private final MilvusClientV2 milvusClient;
    private final MilvusProperties milvusProperties;

    @PostConstruct
    public void init() {
        log.info("");
        log.info("============================================================");
        log.info("📦 MilvusVectorStore 已启用 ({}:{}, collection={}, dim={})",
                milvusProperties.getHost(), milvusProperties.getPort(),
                milvusProperties.getCollectionName(), milvusProperties.getDimension());
        log.info("   索引类型    : {} / 度量: {}", milvusProperties.getIndexType(), milvusProperties.getMetricType());
        log.info("   ⚠️  请确保 Milvus 服务已启动，否则首次向量化写入会失败");
        log.info("============================================================");
        log.info("");

        if (!milvusProperties.isAutoCreateCollection()) {
            log.info("Milvus auto-create-collection 已关闭，跳过集合初始化");
            return;
        }
        try {
            ensureCollection();
        } catch (Exception e) {
            // 初始化失败不应阻止应用启动（Milvus 可能还没起来）
            log.warn("⚠️  Milvus 集合初始化失败（服务可能未就绪），首次写入时会重试: {}", e.getMessage());
        }
    }

    @Override
    public void saveAll(List<EmbeddedChunk> embeddedChunks) {
        if (CollectionUtils.isEmpty(embeddedChunks)) {
            return;
        }

        String collectionName = milvusProperties.getCollectionName();

        // 确保 collection 存在
        ensureCollectionSafe();

        // 组装 Milvus 需要的 JsonObject 列表
        List<com.google.gson.JsonObject> rows = new ArrayList<>(embeddedChunks.size());
        for (EmbeddedChunk ec : embeddedChunks) {
            var chunk = ec.getChunk();
            if (chunk == null || ec.getEmbedding() == null) {
                continue;
            }

            JsonObject row = new JsonObject();
            // 业务唯一主键：doc_{docId}_chunk_{idx}
            String chunkId = String.format("doc_%d_chunk_%d",
                    chunk.getDocumentId(), chunk.getChunkIndex());
            row.addProperty(FIELD_CHUNK_ID, chunkId);
            row.addProperty(FIELD_DOCUMENT_ID, chunk.getDocumentId());
            row.addProperty(FIELD_CHUNK_INDEX, chunk.getChunkIndex());
            row.addProperty(FIELD_CHAPTER_INDEX,
                    chunk.getChapterIndex() != null ? chunk.getChapterIndex() : 0);
            row.addProperty(FIELD_CHAPTER_TITLE,
                    chunk.getChapterTitle() != null ? chunk.getChapterTitle() : "");
            row.addProperty(FIELD_DOCUMENT_NAME,
                    chunk.getDocumentName() != null ? chunk.getDocumentName() : "");
            row.addProperty(FIELD_TOKEN_COUNT,
                    chunk.getTokenCount() != null ? chunk.getTokenCount() : 0);
            row.addProperty(FIELD_CONTENT,
                    chunk.getContent() != null ? chunk.getContent() : "");

            // 向量字段
            JsonArray vec = new JsonArray();
            for (Float f : ec.getEmbedding()) {
                vec.add(f);
            }
            row.add(FIELD_EMBEDDING, vec);

            rows.add(row);
        }

        if (rows.isEmpty()) {
            return;
        }

        InsertReq insertReq = InsertReq.builder()
                .collectionName(collectionName)
                .data(rows)
                .build();

        milvusClient.insert(insertReq);

        EmbeddedChunk first = embeddedChunks.get(0);
        log.info("Milvus 写入完成, collection={}, count={}, documentId={}",
                collectionName, rows.size(),
                first.getChunk() != null ? first.getChunk().getDocumentId() : null);
    }

    @Override
    public void deleteByDocumentId(Long documentId) {
        if (documentId == null) {
            return;
        }
        String collectionName = milvusProperties.getCollectionName();

        // 确保 collection 存在（不存在则无需删除）
        if (!hasCollection()) {
            log.info("Milvus collection 不存在，跳过删除, collection={}", collectionName);
            return;
        }

        String filter = String.format("%s == %d", FIELD_DOCUMENT_ID, documentId);
        DeleteReq deleteReq = DeleteReq.builder()
                .collectionName(collectionName)
                .filter(filter)
                .build();

        milvusClient.delete(deleteReq);
        log.info("Milvus 删除文档向量, collection={}, documentId={}", collectionName, documentId);
    }

    // ==================== 私有方法 ====================

    /**
     * 确保 collection 存在（带异常兜底，失败不抛出）
     *
    private void ensureCollectionSafe() {
        try {
            ensureCollection();
        } catch (Exception e) {
            throw new RuntimeException("Milvus 集合初始化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 若 collection 不存在则创建 + 建索引 + load
     *
    private synchronized void ensureCollection() {
        String collectionName = milvusProperties.getCollectionName();
        if (hasCollection()) {
            log.debug("Milvus collection 已存在, collection={}", collectionName);
            return;
        }

        log.info("开始创建 Milvus collection, name={}, dim={}",
                collectionName, milvusProperties.getDimension());

        // 1) 构建 Schema
        CreateCollectionReq.CollectionSchema schema = milvusClient.createSchema();
        // 主键：自增 VarChar
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_ID)
                .dataType(DataType.VarChar)
                .maxLength(100)
                .isPrimaryKey(true)
                .autoID(true)
                .build());
        // 业务唯一 ID
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_CHUNK_ID)
                .dataType(DataType.VarChar)
                .maxLength(200)
                .build());
        // 文档 ID（过滤删除用）
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_DOCUMENT_ID)
                .dataType(DataType.Int64)
                .build());
        // 切片序号
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_CHUNK_INDEX)
                .dataType(DataType.Int32)
                .build());
        // 章节序号
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_CHAPTER_INDEX)
                .dataType(DataType.Int32)
                .build());
        // 章节标题
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_CHAPTER_TITLE)
                .dataType(DataType.VarChar)
                .maxLength(500)
                .build());
        // 文档名称
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_DOCUMENT_NAME)
                .dataType(DataType.VarChar)
                .maxLength(500)
                .build());
        // token 数
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_TOKEN_COUNT)
                .dataType(DataType.Int32)
                .build());
        // 文本内容
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_CONTENT)
                .dataType(DataType.VarChar)
                .maxLength(VARCHAR_MAX)
                .build());
        // 向量字段
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_EMBEDDING)
                .dataType(DataType.FloatVector)
                .dimension(milvusProperties.getDimension())
                .build());

        // 2) 构建索引参数
        IndexParam.MetricType metricType = toMetricType(milvusProperties.getMetricType());
        IndexParam.IndexType indexType = toIndexType(milvusProperties.getIndexType());

        IndexParam indexParam = IndexParam.builder()
                .fieldName(FIELD_EMBEDDING)
                .indexType(indexType)
                .metricType(metricType)
                .extraParams(Collections.emptyMap())
                .build();

        // 3) 创建 collection（含索引）
        CreateCollectionReq createReq = CreateCollectionReq.builder()
                .collectionName(collectionName)
                .description("Document chunks embedding collection")
                .collectionSchema(schema)
                .indexParams(List.of(indexParam))
                .enableDynamicField(true)
                .build();

        milvusClient.createCollection(createReq);

        // 4) Load collection（同步加载，保证写入后立即可查）
        milvusClient.loadCollection(LoadCollectionReq.builder()
                .collectionName(collectionName)
                .async(false)
                .build());

        log.info("Milvus collection 创建完成并加载, name={}, index={}, metric={}, dim={}",
                collectionName, indexType, metricType, milvusProperties.getDimension());
    }

    private boolean hasCollection() {
        return Boolean.TRUE.equals(milvusClient.hasCollection(
                HasCollectionReq.builder()
                        .collectionName(milvusProperties.getCollectionName())
                        .build()));
    }

    private static IndexParam.MetricType toMetricType(String name) {
        if (name == null || name.isBlank()) {
            return IndexParam.MetricType.COSINE;
        }
        try {
            return IndexParam.MetricType.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("未知的 metric-type: {}, 回退为 COSINE", name);
            return IndexParam.MetricType.COSINE;
        }
    }

    private static IndexParam.IndexType toIndexType(String name) {
        if (name == null || name.isBlank()) {
            return IndexParam.IndexType.AUTOINDEX;
        }
        try {
            return IndexParam.IndexType.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("未知的 index-type: {}, 回退为 AUTOINDEX", name);
            return IndexParam.IndexType.AUTOINDEX;
        }
    }
}
*/
