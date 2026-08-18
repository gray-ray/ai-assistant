package org.grayray.aiassistant.rag.context;

import jakarta.annotation.Resource;
import org.grayray.aiassistant.rag.retrieval.RetrievedChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 上下文组装服务默认实现
 * <p>
 * 流程：minScore 过滤 → maxChunks 条数截断 → maxTokens 预算截断 → 按格式组装文本
 */
@Service
public class ContextAssemblyServiceImpl implements ContextAssemblyService {

    private static final Logger log = LoggerFactory.getLogger(ContextAssemblyServiceImpl.class);

    @Resource
    private ContextProperties contextProperties;

    @Override
    public AssembledContext assemble(List<RetrievedChunk> chunks, String query) {
        ContextFormat format = ContextFormat.fromString(contextProperties.getFormat());
        return assemble(chunks, query, format);
    }

    @Override
    public AssembledContext assemble(List<RetrievedChunk> chunks, String query, ContextFormat format) {
        try {
            if (chunks == null || chunks.isEmpty()) {
                return AssembledContext.empty(format);
            }

            // 1. 截断
            ContextTruncationResult truncation = truncate(chunks);

            if (truncation.getChunks().isEmpty()) {
                log.info("[ContextAssembly] 截断后无片段可用: reason={}", truncation.getTruncateReason());
                return AssembledContext.builder()
                        .text("")
                        .citations(Collections.emptyList())
                        .totalTokens(0)
                        .chunkCount(0)
                        .format(format)
                        .truncateReason(truncation.getTruncateReason())
                        .droppedCount(truncation.getDroppedCount())
                        .build();
            }

            // 2. 按格式组装
            AssembledContext context = switch (format) {
                case MARKDOWN -> formatMarkdown(truncation);
                case PLAIN -> formatPlain(truncation);
                case NUMBERED -> formatNumbered(truncation);
            };

            log.info("[ContextAssembly] chunks={}, totalTokens={}, format={}, truncateReason={}, dropped={}",
                    context.getChunkCount(), context.getTotalTokens(), format,
                    truncation.getTruncateReason(), truncation.getDroppedCount());

            return context;

        } catch (Exception e) {
            log.error("[ContextAssembly] 上下文组装异常，降级为空上下文: {}", e.getMessage(), e);
            return AssembledContext.empty(format);
        }
    }

    // ==================== 截断逻辑 ====================

    /**
     * 按配置对片段做最终截断：minScore → maxChunks → maxTokens（贪心）
     */
    ContextTruncationResult truncate(List<RetrievedChunk> chunks) {
        List<RetrievedChunk> working = new ArrayList<>(chunks);
        int originalSize = working.size();
        String reason = ContextTruncationResult.REASON_NONE;

        // 1. minScore 过滤
        Double minScore = contextProperties.getMinScore();
        if (minScore != null) {
            int before = working.size();
            working = working.stream()
                    .filter(c -> c.getScore() >= minScore)
                    .collect(Collectors.toList());
            if (working.size() < before) {
                reason = ContextTruncationResult.REASON_SCORE;
            }
        }

        // 2. maxChunks 条数截断
        int maxChunks = contextProperties.getMaxChunks();
        if (working.size() > maxChunks) {
            working = new ArrayList<>(working.subList(0, maxChunks));
            reason = ContextTruncationResult.REASON_COUNT;
        }

        // 3. maxTokens 预算截断（贪心：从前往后累加，超出即停）
        int maxTokens = contextProperties.getMaxTokens();
        int accumulated = 0;
        int lastIndex = working.size();
        for (int i = 0; i < working.size(); i++) {
            int tc = estimateTokens(working.get(i));
            if (accumulated + tc > maxTokens && i > 0) {
                // 加上本片段就超预算，截止到上一条（i>0 保证至少保留一条）
                lastIndex = i;
                reason = ContextTruncationResult.REASON_TOKEN;
                break;
            }
            accumulated += tc;
            if (accumulated > maxTokens) {
                // 单条就超预算，仍然保留第一条
                lastIndex = i + 1;
                reason = ContextTruncationResult.REASON_TOKEN;
                break;
            }
        }
        if (lastIndex < working.size()) {
            working = new ArrayList<>(working.subList(0, lastIndex));
        } else {
            accumulated = working.stream().mapToInt(this::estimateTokens).sum();
        }

        return ContextTruncationResult.builder()
                .chunks(working)
                .totalTokens(accumulated)
                .droppedCount(originalSize - working.size())
                .truncateReason(reason)
                .build();
    }

    /**
     * 估算片段 token 数；tokenCount 为 null 时按字符数兜底估算
     */
    private int estimateTokens(RetrievedChunk chunk) {
        if (chunk.getTokenCount() != null && chunk.getTokenCount() > 0) {
            return chunk.getTokenCount();
        }
        String content = chunk.getContent();
        if (content == null) {
            return 0;
        }
        // 简单兜底估算：中文约 2 字/token，英文约 4 字符/token，统一按 2.5 字符/token 粗估
        return Math.max(1, content.length() / 3);
    }

    // ==================== 格式化逻辑 ====================

    /**
     * 编号引用格式（默认）：[n] 《文档》- 章节\n内容\n
     */
    private AssembledContext formatNumbered(ContextTruncationResult truncation) {
        StringBuilder sb = new StringBuilder();
        sb.append("以下是检索到的参考文档片段，请基于这些内容回答问题。\n");
        sb.append("回答时请在相关句子后使用 [n] 标注引用来源（n 为片段编号）。\n");
        sb.append("如果参考内容不足以回答问题，请明确说明，不要编造信息。\n\n");

        List<Citation> citations = new ArrayList<>();
        int totalTokens = 0;

        List<RetrievedChunk> chunks = truncation.getChunks();
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk c = chunks.get(i);
            int idx = i + 1;
            sb.append("[").append(idx).append("] ");
            sb.append("《").append(safe(c.getDocumentName())).append("》");
            if (c.getChapterTitle() != null && !c.getChapterTitle().isBlank()) {
                sb.append(" - ").append(c.getChapterTitle());
            }
            sb.append("\n");
            sb.append(c.getContent() == null ? "" : c.getContent()).append("\n\n");

            totalTokens += estimateTokens(c);
            citations.add(buildCitation(c, idx));
        }

        return AssembledContext.builder()
                .text(sb.toString())
                .citations(citations)
                .totalTokens(totalTokens)
                .chunkCount(citations.size())
                .format(ContextFormat.NUMBERED)
                .truncateReason(truncation.getTruncateReason())
                .droppedCount(truncation.getDroppedCount())
                .build();
    }

    /**
     * Markdown 格式：**[n] 文档 · 章节**\n\n> 内容\n\n
     */
    private AssembledContext formatMarkdown(ContextTruncationResult truncation) {
        StringBuilder sb = new StringBuilder();
        sb.append("### 参考文档\n\n");

        List<Citation> citations = new ArrayList<>();
        int totalTokens = 0;

        List<RetrievedChunk> chunks = truncation.getChunks();
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk c = chunks.get(i);
            int idx = i + 1;
            sb.append("**[").append(idx).append("] ")
                    .append(safe(c.getDocumentName()));
            if (c.getChapterTitle() != null && !c.getChapterTitle().isBlank()) {
                sb.append(" · ").append(c.getChapterTitle());
            }
            sb.append("**\n\n");
            String content = c.getContent() == null ? "" : c.getContent();
            // Markdown 引用块
            for (String line : content.split("\n")) {
                sb.append("> ").append(line).append("\n");
            }
            sb.append("\n");

            totalTokens += estimateTokens(c);
            citations.add(buildCitation(c, idx));
        }

        return AssembledContext.builder()
                .text(sb.toString())
                .citations(citations)
                .totalTokens(totalTokens)
                .chunkCount(citations.size())
                .format(ContextFormat.MARKDOWN)
                .truncateReason(truncation.getTruncateReason())
                .droppedCount(truncation.getDroppedCount())
                .build();
    }

    /**
     * 纯文本格式（仅拼接内容，无编号元数据）
     */
    private AssembledContext formatPlain(ContextTruncationResult truncation) {
        StringBuilder sb = new StringBuilder();
        List<Citation> citations = new ArrayList<>();
        int totalTokens = 0;

        List<RetrievedChunk> chunks = truncation.getChunks();
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk c = chunks.get(i);
            int idx = i + 1;
            if (c.getContent() != null) {
                sb.append(c.getContent()).append("\n\n");
            }
            totalTokens += estimateTokens(c);
            citations.add(buildCitation(c, idx));
        }

        return AssembledContext.builder()
                .text(sb.toString())
                .citations(citations)
                .totalTokens(totalTokens)
                .chunkCount(citations.size())
                .format(ContextFormat.PLAIN)
                .truncateReason(truncation.getTruncateReason())
                .droppedCount(truncation.getDroppedCount())
                .build();
    }

    // ==================== 辅助方法 ====================

    private Citation buildCitation(RetrievedChunk c, int idx) {
        return Citation.builder()
                .index(idx)
                .chunkId(c.getChunkId())
                .documentId(c.getDocumentId())
                .documentName(c.getDocumentName())
                .chapterTitle(c.getChapterTitle())
                .chapterIndex(c.getChapterIndex())
                .chunkIndex(c.getChunkIndex())
                .content(c.getContent())
                .score(c.getScore())
                .build();
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }
}
