package org.grayray.aiassistant.rag.rerank.impl;

import lombok.extern.slf4j.Slf4j;
import org.grayray.aiassistant.rag.rerank.RerankResult;
import org.grayray.aiassistant.rag.rerank.RerankService;
import org.grayray.aiassistant.rag.retrieval.RetrievedChunk;

import java.util.List;

/**
 * 空实现（Noop）：Rerank 开关关闭时使用，直接透传原始结果。
 * <p>
 * 始终返回原序，不调用任何模型，保证主链路零开销。
 * <p>
 * 注：不使用 @Service 注解，由 {@link org.grayray.aiassistant.rag.rerank.RerankConfig} 条件注册。
 */
@Slf4j
public class NoopRerankServiceImpl implements RerankService {

    @Override
    public RerankResult rerank(String query, List<RetrievedChunk> chunks,
                               Integer topM, Double minScore) {
        long start = System.currentTimeMillis();
        log.debug("[Rerank] Noop 模式，透传结果: input={}", chunks == null ? 0 : chunks.size());
        return RerankResult.passThrough(chunks, start, "none");
    }

    @Override
    public boolean isAvailable() {
        return false;
    }
}
