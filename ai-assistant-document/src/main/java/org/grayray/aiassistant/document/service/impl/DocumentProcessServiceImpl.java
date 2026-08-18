package org.grayray.aiassistant.document.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.grayray.aiassistant.document.entity.DocumentInfo;
import org.grayray.aiassistant.document.mapper.DocumentInfoMapper;
import org.grayray.aiassistant.rag.model.EmbeddedChunk;
import org.grayray.aiassistant.rag.model.TextChunk;
import org.grayray.aiassistant.document.service.DocumentChunkService;
import org.grayray.aiassistant.document.service.DocumentProcessService;
import org.grayray.aiassistant.rag.service.EmbeddingService;
import org.grayray.aiassistant.document.service.TextChunkService;
import org.grayray.aiassistant.document.service.TextCleanService;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.List;

/**
 * 文档处理服务实现
 * 1. pdf to text
 * 2. 清洗 text 过滤掉无用的文本（页眉、页脚、页码、多余空格、空行、重复标题）
 * 3. 文本切片（章节 → 段落 → Token长度 → Overlap → Metadata）
 * 4. 向量化 + 向量库存储
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentProcessServiceImpl implements DocumentProcessService {

    private final DocumentInfoMapper documentInfoMapper;
    private final TextCleanService textCleanService;
    private final TextChunkService textChunkService;
    private final DocumentChunkService documentChunkService;
    private final EmbeddingService embeddingService;

    @Async("documentProcessExecutor")
    @Override
    public void processDocument(Long documentId) {
        log.info("开始异步处理文档, documentId={}", documentId);

        DocumentInfo documentInfo = documentInfoMapper.selectById(documentId);
        if (documentInfo == null) {
            log.warn("文档不存在, documentId={}", documentId);
            return;
        }

        // 只处理 PDF
        String fileType = documentInfo.getFileType();
        if (fileType == null || !fileType.equalsIgnoreCase(".pdf")) {
            log.info("非 PDF 文件跳过解析, documentId={}, fileType={}", documentId, fileType);
            // 非 PDF 直接标记为 completed（无内容）
            documentInfo.setProcessStatus("completed");
            documentInfo.setProcessError(null);
            documentInfoMapper.updateById(documentInfo);
            return;
        }

        String storagePath = documentInfo.getStoragePath();
        File pdfFile = new File(storagePath);
        if (!pdfFile.exists()) {
            markFailed(documentInfo, "文件不存在: " + storagePath);
            return;
        }

        // 更新状态为处理中
        documentInfo.setProcessStatus("processing");
        documentInfo.setProcessError(null);
        documentInfoMapper.updateById(documentInfo);

        try (PDDocument pdDocument = Loader.loadPDF(pdfFile)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true); // 按视觉位置排序，保证阅读顺序
            String rawText = stripper.getText(pdDocument);

            // 文本清洗
            String cleanedText = textCleanService.clean(rawText);

            Long knowledgeId = documentInfo.getKnowledgeId();
            Integer chunkVersion = knowledgeId == null
                    ? 1
                    : documentChunkService.nextChunkVersion(documentInfo.getId());

            // 文本切片 (章节 → 段落 → Token长度 → Overlap → Metadata)
            List<TextChunk> chunks = textChunkService.chunk(
                    cleanedText, knowledgeId, documentInfo.getId(), documentInfo.getOriginFileName(), chunkVersion);

            if (knowledgeId != null) {
                documentChunkService.saveTextChunks(knowledgeId, documentInfo.getId(), chunks);
            }

            // 向量化 + 向量库存储（分批调用 embedding model，然后持久化到向量库）
            List<EmbeddedChunk> embeddedChunks = embeddingService.embedAndStore(chunks);
            if (!chunks.isEmpty() && embeddedChunks.isEmpty()) {
                throw new IllegalStateException("文档文本解析成功，但向量化全部失败，请检查 Ollama embedding 模型和 CUDA/驱动环境");
            }
            if (knowledgeId != null) {
                List<TextChunk> vectorizedChunks = embeddedChunks.stream()
                        .filter(ec -> ec.getChunk() != null)
                        .map(EmbeddedChunk::getChunk)
                        .toList();
                documentChunkService.markVectorIds(documentInfo.getId(), chunkVersion, vectorizedChunks);
            }

            documentInfo.setProcessStatus("completed");
            documentInfo.setProcessError(null);
            documentInfoMapper.updateById(documentInfo);
            log.info("文档处理完成, documentId={}, 原始长度={}, 清洗后长度={}, chunks={}, vectors={}",
                    documentId, rawText.length(), cleanedText.length(), chunks.size(), embeddedChunks.size());
        } catch (Exception e) {
            log.error("文档处理失败, documentId={}", documentId, e);
            markFailed(documentInfo, e.getMessage());
        }
    }

    private void markFailed(DocumentInfo documentInfo, String errorMsg) {
        documentInfo.setProcessStatus("failed");
        // 错误信息截断到字段长度以内
        String truncated = errorMsg != null && errorMsg.length() > 990
                ? errorMsg.substring(0, 990) + "..."
                : errorMsg;
        documentInfo.setProcessError(truncated);
        documentInfoMapper.updateById(documentInfo);
    }
}
