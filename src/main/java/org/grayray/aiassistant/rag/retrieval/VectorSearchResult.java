package org.grayray.aiassistant.rag.retrieval;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 向量检索响应结果
 * <p>
 * 封装了检索全流程的输出：已合并去重、按相似度降序、TopN 截断的片段列表，
 * 以及命中数、query 数量、耗时等元信息。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "向量检索结果")
public class VectorSearchResult {

    @Schema(description = "召回的片段列表（按相似度降序，已 TopN 截断）")
    private List<RetrievedChunk> chunks;

    @Schema(description = "命中总条数（过滤后、截断前）")
    private int totalHitCount;

    @Schema(description = "参与检索的 query 数量")
    private int queryCount;

    @Schema(description = "检索总耗时（毫秒，含 embedding + 检索 + 合并）")
    private long costMs;

    /**
     * 便捷方法：判断结果是否为空
     */
    public boolean isEmpty() {
        return chunks == null || chunks.isEmpty();
    }
}
