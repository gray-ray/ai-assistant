package org.grayray.aiassistant.rag.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.vectorstore.SimpleVectorStoreContent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 向量持久化管理器
 * <p>
 * 负责将 {@link SimpleVectorStoreContent} 按文档粒度序列化到本地 JSON 文件，
 * 以及在应用启动时从磁盘加载向量数据到内存。
 * <p>
 * 存储约定：每个文档对应一个文件 {@code doc_{documentId}.json}，
 * 所有文件保存在配置目录 {@code ai.vector-store.persistence-path} 下。
 * 文件内容为 {@code List<SimpleVectorStoreContent>}（该类已自带 Jackson 注解，可直接序列化/反序列化）。
 * <p>
 * Metadata 中包含 {@code documentId} 字段，与 {@code document_info} 表主键一一对应。
 */
@Slf4j
@Component
public class VectorStorePersistenceManager {

    @Value("${ai.vector-store.persistence-path:}")
    private String persistencePath;

    private final ObjectMapper objectMapper;
    private Path baseDir;
    private boolean persistenceEnabled;

    /** 按 documentId 的写入锁，避免并发写入同一文档时的数据错乱 */
    private final Map<Long, ReentrantLock> docLocks = new ConcurrentHashMap<>();

    public VectorStorePersistenceManager() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    @PostConstruct
    public void init() {
        if (!StringUtils.hasText(persistencePath)) {
            persistenceEnabled = false;
            log.info("[VectorPersistence] 未配置 persistence-path，向量仅保存在内存中");
            return;
        }

        this.baseDir = Paths.get(persistencePath).toAbsolutePath();
        File dir = baseDir.toFile();
        if (!dir.exists()) {
            boolean created = dir.mkdirs();
            if (!created) {
                log.warn("[VectorPersistence] 创建向量持久化目录失败: {}", baseDir);
                persistenceEnabled = false;
                return;
            }
        }
        persistenceEnabled = true;
        log.info("[VectorPersistence] 向量持久化已启用, 目录={}", baseDir);
    }

    /**
     * 是否启用了持久化
     */
    public boolean isPersistenceEnabled() {
        return persistenceEnabled;
    }

    /**
     * 将一个文档的所有向量 chunks 保存到磁盘（覆盖写入）
     * <p>
     * 采用先写临时文件再原子替换的策略，防止写入中途崩溃导致数据损坏。
     *
     * @param documentId 文档 ID
     * @param contents   该文档的所有向量内容（metadata 中需包含 documentId）
     */
    public void saveDocumentVectors(Long documentId, List<SimpleVectorStoreContent> contents) {
        if (!persistenceEnabled || documentId == null) {
            return;
        }

        ReentrantLock lock = docLocks.computeIfAbsent(documentId, k -> new ReentrantLock());
        lock.lock();
        try {
            File file = getDocFile(documentId);
            // 先写临时文件再原子替换
            File tmp = new File(file.getParent(), file.getName() + ".tmp");
            objectMapper.writeValue(tmp, contents);
            boolean renamed = tmp.renameTo(file);
            if (!renamed) {
                // renameTo 在跨文件系统时可能失败，fallback 到覆盖写入
                objectMapper.writeValue(file, contents);
                tmp.delete();
            }
            log.debug("[VectorPersistence] 已持久化文档向量, documentId={}, chunkCount={}, file={}",
                    documentId, contents.size(), file.getAbsolutePath());
        } catch (IOException e) {
            log.error("[VectorPersistence] 持久化向量失败, documentId={}", documentId, e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 从磁盘加载所有文档的向量数据
     * <p>
     * 加载时会通过 {@code validator} 校验 documentId 在 {@code document_info} 表中是否存在且未删除，
     * 对于数据库中已不存在的文档对应的脏向量文件，会自动清理。
     * 遇到损坏文件会跳过并记录日志（不影响其他文档及启动流程）。
     *
     * @param validator 校验 documentId 是否有效的函数
     * @return chunkId → SimpleVectorStoreContent 的完整内存映射
     */
    public Map<String, SimpleVectorStoreContent> loadAll(DocumentIdValidator validator) {
        if (!persistenceEnabled) {
            return Collections.emptyMap();
        }

        Map<String, SimpleVectorStoreContent> result = new HashMap<>();
        File dir = baseDir.toFile();
        File[] files = dir.listFiles((d, name) -> name.startsWith("doc_") && name.endsWith(".json"));
        if (files == null || files.length == 0) {
            log.info("[VectorPersistence] 未发现已有向量文件, 从空向量库启动");
            return result;
        }

        int loadedDocs = 0;
        int loadedChunks = 0;
        int skipped = 0;

        for (File file : files) {
            try {
                List<SimpleVectorStoreContent> contents = objectMapper.readValue(
                        file, new TypeReference<List<SimpleVectorStoreContent>>() {});

                if (contents == null || contents.isEmpty()) {
                    log.warn("[VectorPersistence] 跳过空向量文件: {}", file.getName());
                    skipped++;
                    continue;
                }

                // 从 metadata 中提取 documentId 进行校验
                Long docId = extractDocumentId(contents);
                if (docId == null) {
                    log.warn("[VectorPersistence] 向量文件中无有效 documentId，跳过: {}", file.getName());
                    skipped++;
                    continue;
                }

                if (validator != null && !validator.isValid(docId)) {
                    log.warn("[VectorPersistence] 文档(id={})已不存在或已删除，清理脏向量文件: {}",
                            docId, file.getName());
                    safeDelete(file);
                    skipped++;
                    continue;
                }

                for (SimpleVectorStoreContent c : contents) {
                    result.put(c.getId(), c);
                    loadedChunks++;
                }
                loadedDocs++;
            } catch (Exception e) {
                log.error("[VectorPersistence] 加载向量文件失败，跳过: {}", file.getName(), e);
                skipped++;
            }
        }

        log.info("[VectorPersistence] 启动加载完成: 有效文档={}, 向量总数={}, 跳过(损坏/无效)={}",
                loadedDocs, loadedChunks, skipped);
        return result;
    }

    /**
     * 删除指定文档的向量文件
     */
    public void deleteDocumentVectors(Long documentId) {
        if (!persistenceEnabled || documentId == null) {
            return;
        }
        File file = getDocFile(documentId);
        safeDelete(file);
        log.info("[VectorPersistence] 已删除向量文件, documentId={}", documentId);
    }

    // ==================== 私有方法 ====================

    private File getDocFile(Long documentId) {
        return baseDir.resolve("doc_" + documentId + ".json").toFile();
    }

    private void safeDelete(File file) {
        if (file != null && file.exists()) {
            try {
                Files.deleteIfExists(file.toPath());
            } catch (IOException e) {
                log.warn("[VectorPersistence] 删除文件失败: {}", file.getAbsolutePath(), e);
            }
        }
    }

    /**
     * 从 chunks 列表中提取 documentId（取第一个有效 chunk 的 metadata）
     */
    private Long extractDocumentId(List<SimpleVectorStoreContent> contents) {
        for (SimpleVectorStoreContent c : contents) {
            Object docId = c.getMetadata().get("documentId");
            if (docId instanceof Number n) {
                return n.longValue();
            }
            if (docId instanceof String s) {
                try {
                    return Long.parseLong(s);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return null;
    }

    /**
     * documentId 有效性校验函数式接口
     */
    @FunctionalInterface
    public interface DocumentIdValidator {
        boolean isValid(Long documentId);
    }
}
