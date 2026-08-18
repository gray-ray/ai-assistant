package org.grayray.aiassistant.rag.rerank.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.grayray.aiassistant.rag.rerank.RerankProperties;
import org.grayray.aiassistant.rag.rerank.RerankResult;
import org.grayray.aiassistant.rag.rerank.RerankService;
import org.grayray.aiassistant.rag.rerank.RerankedChunk;
import org.grayray.aiassistant.rag.rerank.client.BgeRerankerClient;
import org.grayray.aiassistant.rag.retrieval.RetrievedChunk;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * bge-reranker-v2-m3 重排实现
 * <p>
 * 调用本地部署的 Python rerank 服务，对候选片段做 cross-encoder 精细打分后重排。
 * 主流程：数量检查 → 分批调用 → 分数回填 → 过滤 → 排序 → TopM 截断 → 返回。
 * <p>
 * 降级策略：调用失败时自动降级为按 vectorScore 排序的透传结果，绝不阻塞主链路。
 */
@Slf4j
@RequiredArgsConstructor
public class BgeRerankServiceImpl implements RerankService {

    private final RerankProperties properties;
    private final BgeRerankerClient rerankerClient;

    @Override
    public RerankResult rerank(String query, List<RetrievedChunk> chunks,
                               Integer topM, Double minScore) {
        long start = System.currentTimeMillis();
        int inputCount = chunks == null ? 0 : chunks.size();
        int m = topM != null ? topM : properties.getTopM();
        double min = minScore != null ? minScore : properties.getMinScore();
        String modelName = properties.getModel();

        // 1. 空输入直接返回
        if (CollectionUtils.isEmpty(chunks)) {
            log.debug("[Rerank] 输入为空，直接返回");
            return RerankResult.empty(0, start, modelName);
        }

        // 2. 数量检查：输入 ≤ topM 且 alwaysRerank=false → 直接透传
        if (inputCount <= m && !properties.isAlwaysRerank()) {
            log.debug("[Rerank] 输入数量({}) ≤ topM({}) 且 alwaysRerank=false，直接透传",
                    inputCount, m);
            return RerankResult.passThrough(chunks, start, modelName);
        }

        // 3. 分批调用 reranker
        List<Double> scores;
        int batchCount;
        try {
            List<String> passages = chunks.stream()
                    .map(RetrievedChunk::getContent)
                    .collect(Collectors.toList());
            scores = batchRerank(query, passages);
            batchCount = (int) Math.ceil((double) passages.size() / properties.getBatchSize());
        } catch (Exception e) {
            // 降级：按 vectorScore 透传
            log.warn("[Rerank] 调用 reranker 失败，降级为向量检索原序。model={}, err={}",
                    modelName, e.getMessage());
            RerankResult result = RerankResult.passThrough(chunks, start, modelName);
            logResult(inputCount, result.getChunks().size(), 0, 0,
                    System.currentTimeMillis() - start, true, 0);
            return result;
        }

        // 4. 分数回填 + 融合（可选）
        List<RerankedChunk> ranked = new ArrayList<>(inputCount);
        for (int i = 0; i < inputCount; i++) {
            RetrievedChunk c = chunks.get(i);
            double score = scores.get(i);

            double finalScore = score;
            if (properties.getFusion() != null && properties.getFusion().isEnabled()) {
                double normalizedVectorScore = normalizeVectorScore(chunks, i);
                double alpha = properties.getFusion().getAlpha();
                finalScore = alpha * score + (1 - alpha) * normalizedVectorScore;
            }

            ranked.add(RerankResult.toRerankedChunk(c, finalScore));
        }

        // 5. 过滤 + 排序（按 rerankScore 降序）
        List<RerankedChunk> filtered = ranked.stream()
                .filter(c -> c.getRerankScore() >= min)
                .sorted(Comparator.comparingDouble(RerankedChunk::getRerankScore).reversed())
                .collect(Collectors.toList());

        // 6. TopM 截断
        List<RerankedChunk> resultList = filtered.size() > m
                ? new ArrayList<>(filtered.subList(0, m))
                : filtered;

        long costMs = System.currentTimeMillis() - start;
        double topScore = resultList.isEmpty() ? 0.0 : resultList.get(0).getRerankScore();
        double bottomScore = resultList.isEmpty() ? 0.0
                : resultList.get(resultList.size() - 1).getRerankScore();

        RerankResult result = RerankResult.builder()
                .chunks(resultList)
                .totalInputCount(inputCount)
                .outputCount(resultList.size())
                .topScore(topScore)
                .bottomScore(bottomScore)
                .costMs(costMs)
                .rerankerModel(modelName)
                .passThrough(false)
                .build();

        logResult(inputCount, resultList.size(), topScore, bottomScore, costMs, false, batchCount);
        return result;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    // ==================== 私有方法 ====================

    /**
     * 分批调用 reranker
     *
     * @param query    查询文本
     * @param passages 候选片段文本列表
     * @return 扁平化的分数列表（与 passages 一一对应）
     */
    private List<Double> batchRerank(String query, List<String> passages) {
        List<Double> allScores = new ArrayList<>(passages.size());
        int batchSize = properties.getBatchSize();

        for (int i = 0; i < passages.size(); i += batchSize) {
            int end = Math.min(i + batchSize, passages.size());
            List<String> batch = passages.subList(i, end);
            List<Double> batchScores = rerankerClient.computeScores(query, batch);
            if (batchScores.size() != batch.size()) {
                log.warn("[Rerank] 单批返回数量不匹配: requested={}, got={}",
                        batch.size(), batchScores.size());
                // 补齐缺失位置为 0
                while (batchScores.size() < batch.size()) {
                    batchScores.add(0.0);
                }
            }
            allScores.addAll(batchScores);
        }
        return allScores;
    }

    /**
     * 向量分数的 min-max 归一化（0~1）
     * <p>
     * 用于 fusion 模式，使向量分数与 rerank 分数处于同一量纲。
     */
    private double normalizeVectorScore(List<RetrievedChunk> chunks, int index) {
        if (chunks.size() <= 1) {
            return 1.0;
        }
        double min = chunks.stream().mapToDouble(RetrievedChunk::getScore).min().orElse(0.0);
        double max = chunks.stream().mapToDouble(RetrievedChunk::getScore).max().orElse(1.0);
        if (max - min < 1e-9) {
            return 1.0;
        }
        return (chunks.get(index).getScore() - min) / (max - min);
    }

    private void logResult(int input, int output, double topScore, double bottomScore,
                           long costMs, boolean passThrough, int batches) {
        log.info("[Rerank] model={}, input={}, output={}, topScore={}, bottomScore={}, "
                        + "costMs={}, passThrough={}, batches={}",
                properties.getModel(), input, output,
                String.format("%.4f", topScore),
                String.format("%.4f", bottomScore),
                costMs, passThrough, batches);
    }
}
