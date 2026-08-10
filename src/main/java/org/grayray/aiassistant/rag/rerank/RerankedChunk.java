package org.grayray.aiassistant.rag.rerank;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 重排后的片段
 * <p>
 * 在 {@link org.grayray.aiassistant.rag.retrieval.RetrievedChunk} 基础上追加 rerank 相关字段：
 * 保留原向量检索分数（vectorScore），新增 rerankScore 作为主排序依据。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "重排后的片段")
public class RerankedChunk {

    @Schema(description = "切片唯一 id（格式: doc_{documentId}_chunk_{chunkIndex}）")
    private String chunkId;

    @Schema(description = "所属文档 id")
    private Long documentId;

    @Schema(description = "文档名")
    private String documentName;

    @Schema(description = "切片序号（从 0 开始）")
    private Integer chunkIndex;

    @Schema(description = "文档总切片数")
    private Integer totalChunks;

    @Schema(description = "章节序号")
    private Integer chapterIndex;

    @Schema(description = "章节标题")
    private String chapterTitle;

    @Schema(description = "切片文本内容")
    private String content;

    @Schema(description = "token 估算数")
    private Integer tokenCount;

    @Schema(description = "命中的原始 query")
    private String matchedQuery;

    @Schema(description = "命中次数：多少条 query 命中了该片段")
    @Builder.Default
    private int hitCount = 1;

    @Schema(description = "原向量检索相似度分数（保留用于调试/融合）")
    private double vectorScore;

    @Schema(description = "Rerank 相关性分数（0~1，越高越相关）")
    private double rerankScore;
}
