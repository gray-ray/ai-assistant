package org.grayray.aiassistant.rag.context;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RAG 上下文组装配置项
 * <p>
 * 通过 {@code application.yaml} 中的 {@code ai.rag.context} 前缀注入，
 * 控制最终送入 LLM 的文档片段数量、token 预算、最低分数阈值和组装格式。
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.rag.context")
public class ContextProperties {

    /**
     * 最终送入上下文的最大片段数（硬上限，条数截断）
     */
    private int maxChunks = 5;

    /**
     * 上下文 token 预算上限（超出则按排序依次丢弃最后一条，直到满足预算）
     * 默认 3000，留给系统提示+历史+回答的余量约 128K-3000
     */
    private int maxTokens = 3000;

    /**
     * 最低分数阈值（向量分数或 rerank 分数，取当前使用的 score）。
     * null 表示不额外过滤（沿用检索/rerank 层的 minScore）。
     */
    private Double minScore = null;

    /**
     * 组装格式：numbered | markdown | plain
     */
    private String format = "numbered";
}
