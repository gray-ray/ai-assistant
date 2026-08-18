package org.grayray.aiassistant.document.service;

/**
 * 文档处理服务（PDF → 文本 → 清洗 → 向量化）
 */
public interface DocumentProcessService {

    /**
     * 异步处理文档：PDF 转文本 + 文本清洗
     *
     * @param documentId 文档 ID
     */
    void processDocument(Long documentId);
}
