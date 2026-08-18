package org.grayray.aiassistant.rag.retrieval;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 向量检索命中的片段
 * <p>
 * 封装了单次 TopK 召回或多查询合并后的单个片段信息，包含文档元数据、
 * 文本内容、相似度分数以及命中的原始 query，供下游 Rerank / Answer Generation 使用。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "向量检索命中的片段")
public class RetrievedChunk {

    @Schema(description = "切片唯一 id（格式: doc_{documentId}_chunk_{chunkIndex}）")
    private String chunkId;

    @Schema(description = "所属知识库 id")
    private Long knowledgeId;

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

    @Schema(description = "相似度分数（余弦相似度，范围 [-1, 1]，越接近 1 越相似）")
    private double score;

    @Schema(description = "命中的原始 query（多查询场景下记录得分最高的那条 query）")
    private String matchedQuery;

    @Schema(description = "命中次数：多少条 query 命中了该片段，可作为 rerank 辅助特征")
    @Builder.Default
    private int hitCount = 1;
}
