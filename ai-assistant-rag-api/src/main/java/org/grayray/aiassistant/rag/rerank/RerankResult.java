package org.grayray.aiassistant.rag.rerank;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.grayray.aiassistant.rag.retrieval.RetrievedChunk;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Rerank 重排结果
 * <p>
 * 封装重排后的片段列表（按 rerankScore 降序、已 TopM 截断、已按 minScore 过滤），
 * 以及输入/输出数量、分数范围、耗时、模型名等元信息。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "重排结果")
public class RerankResult {

    @Schema(description = "重排后的片段列表（按 rerankScore 降序）")
    private List<RerankedChunk> chunks;

    @Schema(description = "输入候选总数")
    private int totalInputCount;

    @Schema(description = "输出数量（截断+过滤后）")
    private int outputCount;

    @Schema(description = "最高分（输出列表中第一条）")
    private double topScore;

    @Schema(description = "最低分（输出列表中最后一条）")
    private double bottomScore;

    @Schema(description = "重排总耗时（毫秒）")
    private long costMs;

    @Schema(description = "使用的 reranker 模型名")
    private String rerankerModel;

    @Schema(description = "是否透传（未实际调用 reranker，直接返回原序）")
    @Builder.Default
    private boolean passThrough = false;

    /**
     * 判断结果是否为空
     */
    public boolean isEmpty() {
        return chunks == null || chunks.isEmpty();
    }

    /**
     * 透传结果：将 RetrievedChunk 原样转为 RerankedChunk（rerankScore 置 0），不做重排。
     * <p>
     * 使用场景：开关关闭 / 服务不可用 / 输入过少直接返回。
     *
     * @param chunks  原始检索片段
     * @param startMs 起始时间戳（用于计算 costMs）
     * @param model   模型名（透传时记为 "none"）
     * @return 透传结果
     */
    public static RerankResult passThrough(List<RetrievedChunk> chunks, long startMs, String model) {
        List<RerankedChunk> ranked;
        if (chunks == null || chunks.isEmpty()) {
            ranked = Collections.emptyList();
        } else {
            ranked = chunks.stream()
                    .map(c -> toRerankedChunk(c, c.getScore()))
                    .collect(Collectors.toList());
        }
        return RerankResult.builder()
                .chunks(ranked)
                .totalInputCount(ranked.size())
                .outputCount(ranked.size())
                .topScore(ranked.isEmpty() ? 0.0 : ranked.get(0).getVectorScore())
                .bottomScore(ranked.isEmpty() ? 0.0 : ranked.get(ranked.size() - 1).getVectorScore())
                .costMs(System.currentTimeMillis() - startMs)
                .rerankerModel(model == null ? "none" : model)
                .passThrough(true)
                .build();
    }

    /**
     * 空结果（无候选或全部被过滤）
     */
    public static RerankResult empty(int inputCount, long startMs, String model) {
        return RerankResult.builder()
                .chunks(Collections.emptyList())
                .totalInputCount(inputCount)
                .outputCount(0)
                .topScore(0.0)
                .bottomScore(0.0)
                .costMs(System.currentTimeMillis() - startMs)
                .rerankerModel(model)
                .passThrough(false)
                .build();
    }

    /**
     * RetrievedChunk → RerankedChunk 转换工具
     */
    public static RerankedChunk toRerankedChunk(RetrievedChunk c, double rerankScore) {
        return RerankedChunk.builder()
                .chunkId(c.getChunkId())
                .knowledgeId(c.getKnowledgeId())
                .documentId(c.getDocumentId())
                .documentName(c.getDocumentName())
                .chunkIndex(c.getChunkIndex())
                .totalChunks(c.getTotalChunks())
                .chapterIndex(c.getChapterIndex())
                .chapterTitle(c.getChapterTitle())
                .content(c.getContent())
                .tokenCount(c.getTokenCount())
                .matchedQuery(c.getMatchedQuery())
                .hitCount(c.getHitCount())
                .vectorScore(c.getScore())
                .rerankScore(rerankScore)
                .build();
    }
}
