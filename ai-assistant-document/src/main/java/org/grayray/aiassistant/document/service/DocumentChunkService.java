package org.grayray.aiassistant.document.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.grayray.aiassistant.document.entity.DocumentChunk;
import org.grayray.aiassistant.rag.model.TextChunk;

import java.util.List;

public interface DocumentChunkService extends IService<DocumentChunk> {

    List<DocumentChunk> saveTextChunks(Long knowledgeId, Long documentId, List<TextChunk> chunks);

    void markVectorIds(Long documentId, Integer chunkVersion, List<TextChunk> chunks);

    List<DocumentChunk> listByDocument(Long documentId);

    Integer nextChunkVersion(Long documentId);
}
