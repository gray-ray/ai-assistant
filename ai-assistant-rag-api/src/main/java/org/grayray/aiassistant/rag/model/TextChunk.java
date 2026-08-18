package org.grayray.aiassistant.rag.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

/**
 * 文本切片结果
 */
@Data
@Builder
@Schema(description = "文本切片")
public class TextChunk {

    @Schema(description = "Chunk业务ID")
    private String chunkId;

    @Schema(description = "所属知识库 ID")
    private Long knowledgeId;

    @Schema(description = "切分版本")
    private Integer chunkVersion;

    @Schema(description = "切片文本内容")
    private String content;

    @Schema(description = "全局切片序号（从 0 开始）")
    private Integer chunkIndex;

    @Schema(description = "文档总切片数")
    private Integer totalChunks;

    @Schema(description = "所属章节标题，无章节则为空")
    private String chapterTitle;

    @Schema(description = "章节序号（从 0 开始）")
    private Integer chapterIndex;

    @Schema(description = "所属文档 ID")
    private Long documentId;

    @Schema(description = "PDF页码")
    private Integer pageNumber;

    @Schema(description = "原始文件名")
    private String documentName;

    @Schema(description = "本切片 token 估算数")
    private Integer tokenCount;
}
