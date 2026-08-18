package org.grayray.aiassistant.document.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.grayray.aiassistant.document.entity.DocumentChunk;
import org.grayray.aiassistant.document.mapper.DocumentChunkMapper;
import org.grayray.aiassistant.document.service.DocumentChunkService;
import org.grayray.aiassistant.rag.model.TextChunk;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Service
public class DocumentChunkServiceImpl extends ServiceImpl<DocumentChunkMapper, DocumentChunk>
        implements DocumentChunkService {

    private static final int DEFAULT_CHUNK_VERSION = 1;

    @Override
    public List<DocumentChunk> saveTextChunks(Long knowledgeId, Long documentId, List<TextChunk> chunks) {
        if (knowledgeId == null || documentId == null || CollectionUtils.isEmpty(chunks)) {
            return List.of();
        }

        List<DocumentChunk> entities = new ArrayList<>(chunks.size());
        for (TextChunk chunk : chunks) {
            Integer version = chunk.getChunkVersion() == null ? DEFAULT_CHUNK_VERSION : chunk.getChunkVersion();
            String chunkId = chunk.getChunkId();
            if (chunkId == null || chunkId.isBlank()) {
                chunkId = String.format("doc_%d_v%d_chunk_%d", documentId, version, chunk.getChunkIndex());
            }
            chunk.setChunkId(chunkId);
            chunk.setDocumentId(documentId);
            chunk.setKnowledgeId(knowledgeId);
            chunk.setChunkVersion(version);

            DocumentChunk entity = new DocumentChunk();
            entity.setChunkId(chunkId);
            entity.setDocumentId(documentId);
            entity.setKnowledgeId(knowledgeId);
            entity.setChunkVersion(version);
            entity.setChunkIndex(chunk.getChunkIndex());
            entity.setTotalChunks(chunk.getTotalChunks());
            entity.setContent(chunk.getContent());
            entity.setContentHash(sha256(chunk.getContent()));
            entity.setPageNumber(chunk.getPageNumber());
            entity.setChapterIndex(chunk.getChapterIndex());
            entity.setChapterTitle(chunk.getChapterTitle());
            entity.setTokenCount(chunk.getTokenCount());
            entity.setVectorId(null);
            entity.setMetadata(buildMetadata(chunk));
            entities.add(entity);
        }
        saveBatch(entities);
        return entities;
    }

    @Override
    public void markVectorIds(Long documentId, Integer chunkVersion, List<TextChunk> chunks) {
        if (documentId == null || CollectionUtils.isEmpty(chunks)) {
            return;
        }
        Integer version = chunkVersion == null ? DEFAULT_CHUNK_VERSION : chunkVersion;
        for (TextChunk chunk : chunks) {
            if (chunk == null || chunk.getChunkId() == null) {
                continue;
            }
            update(new LambdaUpdateWrapper<DocumentChunk>()
                    .eq(DocumentChunk::getDocumentId, documentId)
                    .eq(DocumentChunk::getChunkVersion, version)
                    .eq(DocumentChunk::getChunkId, chunk.getChunkId())
                    .set(DocumentChunk::getVectorId, chunk.getChunkId()));
        }
    }

    @Override
    public List<DocumentChunk> listByDocument(Long documentId) {
        if (documentId == null) {
            return List.of();
        }
        return list(new LambdaQueryWrapper<DocumentChunk>()
                .eq(DocumentChunk::getDocumentId, documentId)
                .orderByAsc(DocumentChunk::getChunkVersion)
                .orderByAsc(DocumentChunk::getChunkIndex));
    }

    @Override
    public Integer nextChunkVersion(Long documentId) {
        if (documentId == null) {
            return DEFAULT_CHUNK_VERSION;
        }
        DocumentChunk latest = getOne(new LambdaQueryWrapper<DocumentChunk>()
                .eq(DocumentChunk::getDocumentId, documentId)
                .orderByDesc(DocumentChunk::getChunkVersion)
                .last("LIMIT 1"));
        if (latest == null || latest.getChunkVersion() == null) {
            return DEFAULT_CHUNK_VERSION;
        }
        return latest.getChunkVersion() + 1;
    }

    private static Map<String, Object> buildMetadata(TextChunk chunk) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("chunkId", chunk.getChunkId());
        metadata.put("knowledgeId", chunk.getKnowledgeId());
        metadata.put("documentId", chunk.getDocumentId());
        metadata.put("documentName", chunk.getDocumentName());
        metadata.put("chunkIndex", chunk.getChunkIndex());
        metadata.put("totalChunks", chunk.getTotalChunks());
        metadata.put("chunkVersion", chunk.getChunkVersion());
        metadata.put("chapterIndex", chunk.getChapterIndex());
        metadata.put("chapterTitle", chunk.getChapterTitle());
        metadata.put("pageNumber", chunk.getPageNumber());
        metadata.put("tokenCount", chunk.getTokenCount());
        return metadata;
    }

    private static String sha256(String content) {
        if (content == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
