package org.grayray.aiassistant.rag.rerank;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Rerank 重排配置项
 * <p>
 * 通过 {@code application.yaml} 中的 {@code ai.rerank} 前缀注入，
 * 控制 Rerank 模块的开关、模型、批处理大小、阈值、融合策略等。
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.rerank")
public class RerankProperties {

    /**
     * 总开关，默认关闭（P0 阶段不启用，仅占位）
     */
    private boolean enabled = false;

    /**
     * reranker 模型名称（仅用于日志和响应信息）
     */
    private String model = "bge-reranker-v2-m3";

    /**
     * rerank 服务地址（Python FastAPI 服务）
     */
    private String baseUrl = "http://localhost:8000";

    /**
     * 重排后返回条数（TopM）
     */
    private int topM = 4;

    /**
     * 最低 rerank 分数阈值（0~1），低于此值的结果过滤掉
     */
    private double minScore = 0.3;

    /**
     * 单次调用 reranker 的 passage 批量大小
     */
    private int batchSize = 8;

    /**
     * HTTP 调用超时（毫秒）
     */
    private long timeoutMs = 5000;

    /**
     * 输入数量 ≤ topM 时是否仍要执行重排。
     * 默认 false：输入不足直接透传，省一次模型调用。
     */
    private boolean alwaysRerank = false;

    /**
     * 向量分数融合策略
     */
    private Fusion fusion = new Fusion();

    /**
     * 分数融合配置
     */
    @Data
    public static class Fusion {
        /**
         * 是否开启检索分数融合
         */
        private boolean enabled = false;
        /**
         * rerank 分数权重（0~1），向量分数权重为 1 - alpha
         */
        private double alpha = 0.7;
    }
}
