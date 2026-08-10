# 重排（Rerank）设计文档

> 版本：v1.0
> 日期：2026-08-08
> 项目：ai-assistant（Spring Boot 3.4 + Spring AI 1.1）
> 范围：本文档仅定义对向量检索 TopK 结果的重排序处理；向量检索（前置）、答案生成（后置）由对应设计文档覆盖。

---

## 1. 概述

### 1.1 目标

向量检索召回的 TopK 片段是基于**向量相似度**排序的，存在语义偏差、关键词匹配不足、子问题角度差异导致的排序不准等问题。Rerank（重排）模块使用**更精细的相关性模型**，对召回的候选片段逐一与原始 query 进行细粒度语义打分，重新排序并截断，把真正最相关的片段排在最前面交给下游生成模型，从而提升回答的精准度和信息密度。

### 1.2 在整体链路中的位置

```
上游：向量检索（产出 VectorSearchResult.chunks，TopN 条候选）
        ↓
  本模块：Rerank 重排（精细打分 → 重排 → TopM 截断）
        ↓
下游：答案生成 / Prompt 组装 / 流式回答
```

### 1.3 技术选型

| 层次 | 技术 | 说明 |
|------|------|------|
| Rerank 模型 | Ollama + `bge-reranker-v2-m3` | 本地 Cross-Encoder 模型，中文效果优秀，与 bge-m3 embedding 同系列 |
| 备选方案 | DeepSeek API（LLM 打分） | 当本地无 reranker 模型时，可降级用 LLM pair-wise 打分 |
| 调用方式 | HTTP（Ollama API /embeddings 替代或自定义 endpoint） | 通过 Spring `RestClient` 调用 |
| 批处理 | 分批调用（避免单次请求过长） | batch-size 可配置 |

> **注**：当前 Spring AI 未内置 Rerank 模型抽象，Rerank 模块自行封装服务接口，底层实现可插拔。

---

## 2. 整体流程

```mermaid
flowchart TD
    IN[候选片段列表<br/>VectorSearchResult.chunks] --> CHECK{数量检查<br/><= 直接过<br/>rerank 开关?}
    CHECK -->|无需重排| PASS[直接返回原序]
    CHECK -->|需要重排| BUILD[构造 query-passage 对<br/>{query, doc1}, {query, doc2}, ...]

    BUILD --> BATCH[分批调用 Reranker]
    BATCH --> SCORE[每对得到相关性分数]
    SCORE --> MERGE[分数合并到 chunk]
    MERGE --> SORT[按 rerank 分数降序重排]
    SORT --> TRUNC[TopM 截断]
    TRUNC --> FILTER[可选: 最小分数阈值过滤]
    FILTER --> OUT[RerankResult<br/>重排后的片段列表]
```

---

## 3. Rerank 模型

### 3.1 模型选择：bge-reranker-v2-m3

| 项目 | 说明 |
|------|------|
| 模型 | `bge-reranker-v2-m3` |
| 来源 | BAAI（北京智源）同 bge-m3 embedding 作者团队 |
| 类型 | Cross-Encoder（query + passage 同时输入，输出相关性分数） |
| 语言 | 中英双语，中文效果显著优于 mT5 / mBERT 系列 |
| 输入格式 | `[CLS] query [SEP] passage [SEP]` |
| 输出 | 0~1 之间的相关性得分 |
| 最大长度 | 建议控制在 8K tokens 以内（单条 chunk 500 tokens 远低于上限） |
| 部署 | Ollama 本地部署，或本地 Python 服务封装 |
| 优势 | 与 bge-m3 embedding 同系列，向量检索 + rerank 配合效果最佳 |

### 3.2 模型部署方式（推荐）

**方案 A：Ollama 部署**

```bash
# 拉取 reranker 模型
ollama pull bge-reranker
```

通过 Ollama 的 `/api/embeddings` 或自定义 endpoint 调用。若 Ollama 原生不支持 cross-encoder 模式，则采用方案 B。

**方案 B：本地 Python 服务（推荐，更可控）**

用 FastAPI + `FlagEmbedding` 库封装一个极简 rerank 服务，提供 `/rerank` 接口：

```python
# 伪代码
from flagembedding import FlagReranker
reranker = FlagReranker("BAAI/bge-reranker-v2-m3")

@app.post("/rerank")
def rerank(query: str, passages: List[str]):
    pairs = [[query, p] for p in passages]
    scores = reranker.compute_score(pairs, normalize=True)
    return {"scores": scores}
```

**建议**：初期先接方案 B（Python 服务），效果与可控性最好。后续若 Ollama 完善 rerank 支持再切换。

### 3.3 降级方案：LLM 打分

若本地无法部署 reranker 模型，可使用 DeepSeek Chat 做 pair-wise 或 point-wise 打分作为降级：

- **point-wise**：让 LLM 对每个 chunk 单独打 0~5 分的相关性评分
- **pair-wise**：让 LLM 在两个候选中选更相关的那个（成本更高但更准）

降级方案作为 `RerankService` 的一个实现类，通过配置切换。

---

## 4. 接口设计

### 4.1 核心接口

```java
public interface RerankService {

    /**
     * 对候选片段进行重排序
     *
     * @param query    原始查询（用户真实问题，非扩展子问题）
     * @param chunks   待重排的候选片段列表
     * @param topM     返回前 M 条，null 则使用配置默认值
     * @param minScore 最低 rerank 分数阈值（0~1），null 则使用配置默认值
     * @return 重排后的结果（按 rerank 分数降序，已截断、已过滤）
     */
    RerankResult rerank(String query, List<RetrievedChunk> chunks,
                        Integer topM, Double minScore);

    /**
     * 便捷方法，使用默认参数
     */
    default RerankResult rerank(String query, List<RetrievedChunk> chunks) {
        return rerank(query, chunks, null, null);
    }

    /**
     * 是否可用（模型/服务是否就绪）
     */
    boolean isAvailable();
}
```

### 4.2 数据结构

```java
@Data
@Builder
public class RerankResult {
    private List<RerankedChunk> chunks;     // 重排后的片段列表（按 rerankScore 降序）
    private int totalInputCount;            // 输入候选总数
    private int outputCount;                // 输出数量
    private double topScore;                // 最高分
    private double bottomScore;             // 最低分（输出列表中最后一条）
    private long costMs;                    // 重排总耗时
    private String rerankerModel;           // 使用的 reranker 模型名
}
```

```java
@Data
@Builder
public class RerankedChunk {
    // 来自 RetrievedChunk 的全部字段
    private String chunkId;
    private Long documentId;
    private String documentName;
    private Integer chunkIndex;
    private Integer chapterIndex;
    private String chapterTitle;
    private String content;
    private Integer tokenCount;

    // 原向量检索分数（保留用于调试/融合）
    private double vectorScore;

    // Rerank 后的分数（0~1，越高越相关）
    private double rerankScore;
}
```

---

## 5. 主流程设计

### 5.1 主流程伪代码

```java
public RerankResult rerank(String query, List<RetrievedChunk> chunks,
                           Integer topM, Double minScore) {
    long start = System.currentTimeMillis();
    int inputCount = chunks.size();
    int m = firstNonNull(topM, defaultTopM);
    double min = firstNonNull(minScore, defaultMinScore);

    // 1. 数量检查：过少则直接返回，省一次模型调用
    if (CollectionUtils.isEmpty(chunks)) {
        return emptyResult(inputCount, start);
    }
    if (chunks.size() <= m && !alwaysRerank) {
        return passThroughResult(chunks, start);
    }

    // 2. 分批调用 reranker
    List<String> passages = chunks.stream().map(RetrievedChunk::getContent).toList();
    List<Double> scores = batchRerank(query, passages);

    // 3. 分数回填
    List<RerankedChunk> ranked = new ArrayList<>();
    for (int i = 0; i < chunks.size(); i++) {
        RetrievedChunk c = chunks.get(i);
        double score = scores.get(i);
        ranked.add(RerankedChunk.builder()
                .chunkId(c.getChunkId())
                .documentId(c.getDocumentId())
                .documentName(c.getDocumentName())
                .chunkIndex(c.getChunkIndex())
                .chapterIndex(c.getChapterIndex())
                .chapterTitle(c.getChapterTitle())
                .content(c.getContent())
                .tokenCount(c.getTokenCount())
                .vectorScore(c.getScore())
                .rerankScore(score)
                .build());
    }

    // 4. 过滤 + 排序
    List<RerankedChunk> filtered = ranked.stream()
            .filter(c -> c.getRerankScore() >= min)
            .sorted(Comparator.comparingDouble(RerankedChunk::getRerankScore).reversed())
            .toList();

    // 5. TopM 截断
    List<RerankedChunk> resultList = filtered.size() > m
            ? filtered.subList(0, m) : filtered;

    return RerankResult.builder()
            .chunks(resultList)
            .totalInputCount(inputCount)
            .outputCount(resultList.size())
            .topScore(resultList.isEmpty() ? 0.0 : resultList.get(0).getRerankScore())
            .bottomScore(resultList.isEmpty() ? 0.0 : resultList.get(resultList.size() - 1).getRerankScore())
            .costMs(System.currentTimeMillis() - start)
            .rerankerModel(rerankerModelName)
            .build();
}
```

### 5.2 分批调用策略

```java
private List<Double> batchRerank(String query, List<String> passages) {
    List<Double> allScores = new ArrayList<>();
    int batchSize = rerankBatchSize;  // 默认 8

    for (int i = 0; i < passages.size(); i += batchSize) {
        int end = Math.min(i + batchSize, passages.size());
        List<String> batch = passages.subList(i, end);

        // 调用 reranker 服务
        List<Double> batchScores = rerankerClient.computeScores(query, batch);
        allScores.addAll(batchScores);
    }
    return allScores;
}
```

**参数：**

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `topM` | `4` | 重排后返回条数 |
| `minScore` | `0.3` | rerank 最低分数阈值 |
| `batchSize` | `8` | 单次调用 reranker 的 passage 数 |
| `alwaysRerank` | `false` | 输入数量 ≤ topM 时是否仍要重排 |

---

## 6. 与检索分数融合策略（可选增强）

Rerank 分数作为主排序依据，但在以下场景可以考虑与向量检索分数融合，获得更稳定效果：

### 6.1 加权融合

```
finalScore = α * rerankScore + (1 - α) * normalizedVectorScore
```

其中 `normalizedVectorScore` 是向量检索分数的 min-max 归一化结果，`α` 建议取 `0.7 ~ 0.8`，以 rerank 为主。

**适用场景**：reranker 模型较弱或部署为 LLM 打分（不稳定）时，融合向量分数提供兜底。

### 6.2 加权融合的配置项

```yaml
ai:
  rerank:
    fusion:
      enabled: false          # 默认关闭，纯 rerank 排序
      alpha: 0.7              # rerank 权重
```

建议 P0 阶段直接用纯 rerank 分数排序，简单有效；P1 阶段根据效果再决定是否开启融合。

---

## 7. 模块代码结构

```
com.grayray.aiassistant.
└── rerank/
    ├── RerankService.java                    // 重排服务接口
    ├── RerankResult.java                     // 重排结果
    ├── RerankedChunk.java                    // 重排后的片段
    ├── RerankProperties.java                 // 配置属性类
    ├── impl/
    │   ├── BgeRerankServiceImpl.java         // bge-reranker-v2-m3 实现（Python 服务）
    │   ├── LlmRerankServiceImpl.java         // LLM 打分降级实现（可选）
    │   └── NoopRerankServiceImpl.java        // 空实现（开关关闭时使用，直接透传）
    └── client/
        └── BgeRerankerClient.java            // 调用 Python rerank 服务的 HTTP 客户端
```

### 7.1 开关与降级

通过 `ai.rerank.enabled` 控制是否启用：

- `enabled: false` → 注入 `NoopRerankServiceImpl`，直接返回原序（透传）
- `enabled: true` 但服务不可达 → 初始化时抛出 WARN，运行时自动降级为原序，返回 vector 排序结果

这样保证 rerank 模块故障时不影响主链路可用性。

---

## 8. 配置项

```yaml
ai:
  rerank:
    enabled: false                     # 总开关，默认关闭（P0 阶段不启用）
    model: bge-reranker-v2-m3          # 模型名称
    base-url: http://localhost:8000    # rerank 服务地址（Python 服务）
    top-m: 4                           # 重排后返回条数
    min-score: 0.3                     # 最低 rerank 分数
    batch-size: 8                      # 单次调用批量大小
    timeout-ms: 5000                   # 调用超时
    always-rerank: false               # 输入少于 topM 时是否仍重排
    fusion:
      enabled: false                   # 是否开启检索分数融合
      alpha: 0.7                       # rerank 分数权重
```

```java
@Data
@ConfigurationProperties(prefix = "ai.rerank")
public class RerankProperties {
    private boolean enabled = false;
    private String model = "bge-reranker-v2-m3";
    private String baseUrl = "http://localhost:8000";
    private int topM = 4;
    private double minScore = 0.3;
    private int batchSize = 8;
    private long timeoutMs = 5000;
    private boolean alwaysRerank = false;
    // fusion...
}
```

---

## 9. 日志与可观测性

每次重排打一条 INFO 日志：

```
[Rerank] model=bge-reranker-v2-m3, input=6, output=4,
 topScore=0.91, bottomScore=0.58, costMs=230,
 passThrough=false, batches=1
```

核心指标：

| 指标 | 说明 |
|------|------|
| `rerank.qps` | 重排 QPS |
| `rerank.latency.ms` | 重排总耗时（含 HTTP 调用 + 排序） |
| `rerank.input_count.avg` | 输入候选平均数 |
| `rerank.output_count.avg` | 输出数量 |
| `rerank.top_score.avg` | 最高分平均值 |
| `rerank.empty_rate` | 重排后为空的占比 |
| `rerank.passthrough_rate` | 透传率（输入不足直接返回） |
| `rerank.error_count` | 调用失败次数 |

---

## 10. 异常与降级策略

| 场景 | 策略 |
|------|------|
| Rerank 服务不可达（启动时） | 记录 ERROR，自动注册 `NoopRerankService`，主链路不受影响 |
| Rerank 服务调用超时/失败（运行时） | 降级为按原始 vectorScore 排序返回，记录 WARN |
| 某条 passage 打分失败 | 该条分数置为 0，保留在结果末尾，记录 WARN |
| 输入片段为空 | 返回空结果 |
| 输入片段数 ≤ topM 且 alwaysRerank=false | 直接透传（保留原 vector 顺序），节省模型调用 |
| 输出为空（全部低于 minScore） | 返回空列表，下游按无检索结果处理 |

**核心原则**：Rerank 是"锦上添花"的优化层，任何异常都必须降级为向量检索原始结果，**绝不阻塞主链路**。

---

## 11. 性能评估

### 11.1 延迟估算（bge-reranker-v2-m3，本地 GPU）

| 候选数 | batch-size | 预估耗时 |
|--------|-----------|----------|
| 6 条 | 8 | 80~150 ms |
| 10 条 | 8 | 120~200 ms |
| 20 条 | 8（3 批） | 300~500 ms |

> 具体耗时取决于硬件（GPU / CPU）、序列长度。chunk 500 tokens 时，单条推理很快。

### 11.2 效果提升预期

| 指标 | 纯向量检索 | + Rerank | 提升 |
|------|-----------|----------|------|
| Recall@3 | ~65% | ~80% | +15pp |
| MRR | ~0.55 | ~0.72 | +30% |
| NDCG@3 | ~0.58 | ~0.75 | +29% |

> 数值为常见中文场景经验值，实际需结合自有评估集测试。

---

## 12. 接入方式

### 12.1 在检索后调用

```java
// 1. 向量检索
VectorSearchResult searchResult = vectorSearchService.search(request);

// 2. Rerank（可选，由配置开关控制）
List<RetrievedChunk> candidates = searchResult.getChunks();
RerankResult rerankResult;
if (rerankService.isAvailable()) {
    // 用 originalQuery（用户原始问题）做 rerank，而非扩展子查询
    rerankResult = rerankService.rerank(originalQuery, candidates);
} else {
    rerankResult = RerankResult.passThrough(candidates);
}

// 3. 交给下游答案生成
return rerankResult;
```

### 12.2 注意点

- **Rerank 使用的 query 是用户原始问题**（`originalQuery`），而不是 Query Expansion 拆解出的子查询。因为 reranker 要衡量"候选片段是否真正回答了用户想问的问题"，用原始问题更准确。
- 多查询召回的合并结果直接喂给 reranker，由 reranker 统一排序，消弭不同子查询带来的排序偏差。

---

## 13. 迭代计划

| 阶段 | 内容 | 价值 |
|------|------|------|
| P0 | `NoopRerankService` 空实现 + 接口定义 + 配置开关 | 先占位，上下游接口对齐，主链路跑通 |
| P1 | 部署 Python bge-reranker-v2-m3 服务 + `BgeRerankServiceImpl` 实现 | 真正提升检索质量，核心价值 |
| P2 | 加权融合策略 + 调优 α 参数 | 进一步提升稳定性 |
| P3 | LLM pair-wise 降级实现 | reranker 不可用时的兜底 |
| P4 | 动态 TopM（根据分数分布自适应截断） + 离线评估集 | 持续效果迭代 |

---

## 14. 附录：术语表

| 术语 | 含义 |
|------|------|
| Rerank / Reranker | 重排 / 重排器，对初步召回结果做精细排序 |
| Cross-Encoder | 交叉编码器，把 query 和 passage 同时输入模型打分，精度高但速度慢 |
| Bi-Encoder | 双编码器，query 和 passage 分别编码为向量，即向量检索用的 embedding 模型 |
| bge-reranker-v2-m3 | BAAI 出品的多语言 cross-encoder reranker 模型 |
| Pair-wise | 两两比较的排序方式 |
| Point-wise | 单独打分的排序方式 |
| MRR | Mean Reciprocal Rank，第一个相关文档排名倒数的平均值 |
| NDCG | Normalized Discounted Cumulative Gain，归一化折损累计增益 |
| TopM | 重排后最终返回的片段数 |
