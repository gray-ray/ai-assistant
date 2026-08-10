package org.grayray.aiassistant.rag.retrieval;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 向量检索配置项
 * <p>
 * 通过 {@code application.yaml} 中的 {@code ai.vector-search} 前缀注入，
 * 用于调优 TopK 召回链路的关键参数：单查询召回数、最终 TopN、最低相似度阈值等。
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.vector-search")
public class VectorSearchProperties {

    /**
     * 单条 query 召回的候选片段数（TopK）
     */
    private int topKPerQuery = 4;

    /**
     * 多查询合并去重后最终返回的最大片段数（TopN）
     */
    private int finalTopN = 6;

    /**
     * 最低相似度阈值（含），低于此值的结果直接丢弃
     */
    private double minScore = 0.5;

    /**
     * 是否启用元数据过滤（documentIds 等）
     */
    private boolean enableMetadataFilter = true;
}
