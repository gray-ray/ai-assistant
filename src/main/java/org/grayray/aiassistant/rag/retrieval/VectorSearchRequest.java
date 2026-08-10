package org.grayray.aiassistant.rag.retrieval;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 向量检索请求对象
 * <p>
 * 接收来自 Query Router 产出的查询文本列表及各种调优参数。所有数值参数
 * 允许为 null，null 时使用 {@code VectorSearchProperties} 中的默认值。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "向量检索请求")
public class VectorSearchRequest {

    @Schema(description = "查询文本列表（来自 Query Router，通常 1~4 条）", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<String> queries;

    @Schema(description = "单查询 TopK，null 使用默认值")
    private Integer topKPerQuery;

    @Schema(description = "合并后最终 TopN，null 使用默认值")
    private Integer finalTopN;

    @Schema(description = "最低相似度阈值（含），null 使用默认值")
    private Double minScore;

    @Schema(description = "可选：限定检索的文档范围，空则检索全部文档")
    private List<Long> documentIds;

    /**
     * 便捷方法：单查询构造
     */
    public static VectorSearchRequest of(String query) {
        return VectorSearchRequest.builder()
                .queries(List.of(query))
                .build();
    }
}
