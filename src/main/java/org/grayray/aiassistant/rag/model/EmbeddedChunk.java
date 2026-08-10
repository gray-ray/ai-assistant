package org.grayray.aiassistant.rag.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;
import org.grayray.aiassistant.document.model.TextChunk;

import java.util.List;

/**
 * 已向量化的文本切片
 * <p>
 * 在 {@link TextChunk} 基础上附加 embedding 向量。
 */
@Data
@Builder
@Schema(description = "已向量化的文本切片")
public class EmbeddedChunk {

    @Schema(description = "原始切片信息")
    private TextChunk chunk;

    @Schema(description = "embedding 向量（浮点数列表）")
    private List<Float> embedding;

    @Schema(description = "向量维度")
    private Integer dimension;

    @Schema(description = "向量模型名称")
    private String embeddingModel;
}