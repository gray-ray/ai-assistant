# RAG 上下文组装与 Prompt 功能设计文档

> 版本：v1.0
> 日期：2026-08-09
> 项目：ai-assistant（Spring Boot 3.4 + Spring AI 1.1）
> 范围：Rerank → TopN → Context 组装 → Prompt 全链路实现设计

---

## 1. 概述

### 1.1 背景与目标

当前项目的 RAG 管线已完成 **Query 路由 → 向量检索 → Rerank** 前三阶段，但检索结果始终未注入到 LLM 生成的 Prompt 中（`ChatServiceImpl` 中有明确 TODO 标记）。

本次实现目标是将 RAG 链路收尾，打通 **Rerank → TopN → Context 组装 → Prompt** 四个环节，让 LLM 基于检索到的文档片段生成回答，并在前端展示引用来源。

### 1.2 在整体链路中的位置

```
用户消息
   │
   ▼
Query Router（已实现）
   │
   ▼
向量检索 & TopK 召回（已实现）
   │
   ▼
Rerank 重排（已实现，默认关闭）
   │
   ▼
┌─────────────────────────────────────────┐
│  本次实现：                              │
│  ① TopN 最终截断（策略化、可配置）       │
│  ② Context 组装（结构化引用 + token 预算）│
│  ③ Prompt 模板（RAG 系统提示 + 注入）    │
│  ④ ChatServiceImpl 接线（同步 + 流式）   │
│  ⑤ 引用来源返回（SSE / 响应 DTO）        │
└─────────────────────────────────────────┘
   │
   ▼
LLM 生成（DeepSeek）
```

---

## 2. 整体架构设计

### 2.1 四阶段流程

```
Rerank（已实现，可选）
   │ 输出：List<RerankedChunk> 或 List<RetrievedChunk>
   ▼
① TopN 最终截断
   │ 按 token 预算 & 条数上限做最终截断
   │ 输出：List<RetrievedChunk>（最终用于上下文的片段）
   ▼
② Context 组装
   │ 将片段格式化为带编号引用的上下文文本块
   │ 输出：String assembledContext + List<Citation> citations
   ▼
③ Prompt 构建
   │ RAG 系统提示 + 上下文 + 用户问题
   │ 输出：List<Message> promptMessages
   ▼
④ 生成 & 引用返回
   │ LLM 生成回答
   │ 将引用信息通过 done 事件 / 响应返回前端
```

### 2.2 模块划分

| 模块 | 职责 | 包路径 |
|------|------|--------|
| `rag/context/` | 上下文组装服务 | `org.grayray.aiassistant.rag.context` |
| `rag/prompt/` | RAG Prompt 模板管理 | `org.grayray.aiassistant.rag.prompt` |
| `chat/` 内改造 | ChatServiceImpl 接线 + 引用返回 | 现有 `chat.service.impl` |

---

## 3. 阶段一：TopN 最终截断

### 3.1 设计目标

Rerank 后（或向量检索后，当 rerank 关闭时）需要做**最终的 TopN 截断**，确保送入上下文的片段数量和 token 总量在合理范围内。

虽然 `VectorSearchServiceImpl` 已经做了 `finalTopN` 截断，但那是检索层的硬性条数限制。这里的 TopN 是**生成层的最终把关**，负责：

1. **Token 预算控制**：确保所有片段总 token 数不超过 LLM 上下文窗口的配额
2. **条数上限兜底**：配置 `context-max-chunks` 作为最终条数上限
3. **质量过滤**：可配置最低分数阈值，过滤低质量片段

### 3.2 配置项（新增到 `ai.rag.context`）

```yaml
ai:
  rag:
    context:
      # 最终送入上下文的最大片段数（硬上限）
      max-chunks: 5
      # 上下文 token 预算上限（超出则按排序依次丢弃最后一条，直到满足预算）
      # 默认 3000，留给系统提示+历史+回答的余量约 32K-3000=29K
      max-tokens: 3000
      # 最低分数阈值（向量分数或 rerank 分数，取当前使用的 score）
      # null 表示不额外过滤（沿用检索/rerank 层的 minScore）
      min-score: null
      # 组装格式：numbered | markdown | plain
      format: numbered
```

### 3.3 截断策略（贪心算法）

输入：已按相关性分数降序排列的 `List<RetrievedChunk>`

```
1. 先按 min-score 过滤（如果配置了）
2. 再按 max-chunks 截断条数
3. 最后按 max-tokens 做 token 预算截断：
   - 从第 1 条开始累加 tokenCount
   - 累加至超出 max-tokens 时，丢弃当前及之后的所有片段
   - 保证高相关度片段优先入上下文
4. 返回截断后的列表 + 总 token 数
```

### 3.4 数据结构

```java
@Data
@Builder
public class ContextTruncationResult {
    /** 最终入选的片段（已按相关性降序） */
    private List<RetrievedChunk> chunks;
    /** 入选片段总 token 数 */
    private int totalTokens;
    /** 被截断丢弃的片段数 */
    private int droppedCount;
    /** 截断原因：SCORE / COUNT / TOKEN / NONE */
    private String truncateReason;
}
```

---

## 4. 阶段二：Context 组装

### 4.1 设计目标

将截断后的 `List<RetrievedChunk>` 组装成**结构化、带引用编号、包含元数据**的上下文文本块，注入到 Prompt 中供 LLM 使用。

同时产出**引用信息列表**（citations），供前端在回答末尾展示来源。

### 4.2 组装格式

#### 格式 A：编号引用（numbered）—— 默认格式

```
以下是检索到的参考文档片段，请基于这些内容回答问题。
回答时请在相关句子后使用 [n] 标注引用来源（n 为片段编号）。
如果参考内容不足以回答问题，请明确说明。

[1] 《文档名称.pdf》- 第3章 章节标题
内容文本内容文本内容文本内容文本内容文本...

[2] 《另一文档.pdf》- 第1章 章节标题
内容文本内容文本...
```

**优势**：
- LLM 容易学会标注引用
- 前端可解析 `[n]` 做悬浮展示
- 结构清晰，模型易理解

#### 格式 B：Markdown 引用（markdown）

```markdown
### 参考文档

**[1] 文档名称.pdf · 第3章 章节标题**

> 内容文本...
```

#### 格式 C：纯文本（plain）

仅拼接内容，无编号元数据（用于调试或极简场景）。

### 4.3 接口设计

```java
public interface ContextAssemblyService {

    /**
     * 将检索片段组装为上下文文本
     *
     * @param chunks  已排序/截断的片段列表
     * @param query   用户原始问题（用于上下文前导提示）
     * @return 组装结果（上下文文本 + 引用列表）
     */
    AssembledContext assemble(List<RetrievedChunk> chunks, String query);

    /**
     * 指定格式组装
     */
    AssembledContext assemble(List<RetrievedChunk> chunks, String query, ContextFormat format);
}
```

### 4.4 数据结构

```java
@Data
@Builder
public class AssembledContext {
    /** 组装后的完整上下文文本（含前导说明 + 各片段） */
    private String text;
    /** 引用列表（按编号顺序，与 text 中 [n] 对应） */
    private List<Citation> citations;
    /** 总 token 数（所有片段 content tokenCount 之和） */
    private int totalTokens;
    /** 片段数量 */
    private int chunkCount;
    /** 组装格式 */
    private ContextFormat format;

    /** 是否为空（无片段时为 true） */
    public boolean isEmpty() {
        return chunkCount == 0;
    }
}
```

```java
@Data
@Builder
public class Citation {
    /** 引用编号（从 1 开始，对应上下文 [n]） */
    private int index;
    /** 片段 ID */
    private String chunkId;
    /** 文档 ID */
    private Long documentId;
    /** 文档名称 */
    private String documentName;
    /** 章节标题（可能为 null） */
    private String chapterTitle;
    /** 章节序号（可能为 null） */
    private Integer chapterIndex;
    /** 片段在文档中的序号 */
    private Integer chunkIndex;
    /** 片段内容（完整，用于前端悬浮展示） */
    private String content;
    /** 相关性分数（用于排序展示） */
    private double score;
}
```

```java
public enum ContextFormat {
    NUMBERED,   // 编号引用 [1] [2] ...
    MARKDOWN,   // Markdown 标题 + 引用块
    PLAIN       // 纯文本拼接
}
```

### 4.5 核心实现思路

**`NumberedContextAssembler`（默认实现）**：

```java
// 伪代码
StringBuilder sb = new StringBuilder();
sb.append("以下是检索到的参考文档片段，请基于这些内容回答问题。\n");
sb.append("回答时请在相关句子后使用 [n] 标注引用来源。\n");
sb.append("如果参考内容不足以回答问题，请明确说明，不要编造信息。\n\n");

List<Citation> citations = new ArrayList<>();
for (int i = 0; i < chunks.size(); i++) {
    RetrievedChunk c = chunks.get(i);
    int idx = i + 1;
    sb.append("[").append(idx).append("] ");
    // 元数据行
    sb.append("《").append(c.getDocumentName()).append("》");
    if (c.getChapterTitle() != null) {
        sb.append(" - ").append(c.getChapterTitle());
    }
    sb.append("\n");
    // 内容行
    sb.append(c.getContent()).append("\n\n");

    citations.add(Citation.builder()
            .index(idx)
            .chunkId(c.getChunkId())
            .documentId(c.getDocumentId())
            .documentName(c.getDocumentName())
            .chapterTitle(c.getChapterTitle())
            .chapterIndex(c.getChapterIndex())
            .chunkIndex(c.getChunkIndex())
            .content(c.getContent())
            .score(c.getScore())
            .build());
}
```

---

## 5. 阶段三：Prompt 功能实现

### 5.1 设计目标

建立 **RAG 专用的 Prompt 模板体系**，替代当前硬编码的 `"你是一个有帮助的AI助手。"`。

包含：
1. **RAG 系统提示模板**：指导 LLM 基于上下文回答
2. **无上下文系统提示**：无检索结果时的降级提示
3. **模板管理工具**：与现有 `AbstractQueryComponent` 的模板加载机制保持一致

### 5.2 Prompt 模板设计

#### 5.2.1 模板文件结构

```
src/main/resources/prompts/
├── query/                              # 已有（查询预处理模板）
│   ├── classify.st
│   ├── rewrite.st
│   └── expand.st
└── rag/                                # 新增（RAG 生成模板）
    ├── rag-system.st                   # RAG 系统提示（有上下文）
    └── fallback-system.st              # 无上下文时的系统提示
```

#### 5.2.2 RAG 系统提示模板（`rag-system.st`）

```
你是一个专业的文档问答助手，请基于提供的参考文档片段回答用户问题。

【回答规则】
1. 只使用参考文档中的信息回答，不要编造或引入外部知识
2. 如果参考文档中没有相关信息，请明确回答"根据现有文档，无法回答该问题"
3. 回答时在相关内容后使用 [n] 标注引用来源（n 为参考片段的编号）
4. 引用多个来源时使用 [1][2] 格式标注
5. 保持回答准确、简洁、条理清晰
6. 优先引用编号靠前的文档片段（相关度更高）

【参考文档】
{context}

【用户问题】
{question}
```

> 说明：`{context}` 注入 `AssembledContext.text`，`{question}` 注入用户原始问题。
> 注意：与现有 `AbstractQueryComponent` 使用相同的 `{key}` 占位符风格。

#### 5.2.3 无上下文提示模板（`fallback-system.st`）

```
你是一个有帮助的AI助手。
当前未检索到相关文档，请直接回答用户的问题。
如果问题涉及特定文档内容，请告知用户暂无相关资料。
```

### 5.3 Prompt 构建服务

```java
@Service
public class RagPromptService {

    @Value("classpath:prompts/rag/rag-system.st")
    private Resource ragSystemTemplate;

    @Value("classpath:prompts/rag/fallback-system.st")
    private Resource fallbackSystemTemplate;

    /**
     * 构建 RAG Prompt 的消息列表
     *
     * @param systemPrompt 用户自定义系统提示（可选，为 null 时使用内置模板）
     * @param context      组装好的上下文（可能为空）
     * @param question     用户当前问题
     * @param history      历史消息（不含当前问题）
     * @return 完整的 Prompt 消息列表
     */
    public List<Message> buildRagMessages(String systemPrompt,
                                          AssembledContext context,
                                          String question,
                                          List<ChatMessage> history);
}
```

### 5.4 构建逻辑

```
1. 确定系统提示：
   - 如果用户传入了 systemPrompt → 使用用户的（业务方自定义）
   - 否则如果 context 非空 → 使用 rag-system 模板，注入 {context} 和 {question}
   - 否则（context 为空） → 使用 fallback-system 模板

2. 组装消息列表：
   [SystemMessage(系统提示)]
   + [历史 User/Assistant 消息（按顺序）]
   + [UserMessage(当前用户问题)]

3. 注意：当前问题已经在系统提示中以【用户问题】形式给出，
   历史消息中最后一条用户消息（即当前问题）需要去重。
```

> **关于去重**：`loadHistoryMessages()` 会加载包括当前用户消息在内的全部历史。
> 在注入 RAG 系统提示时，当前问题已经写入系统提示的 `{question}` 位置，
> 因此历史消息中需要排除最后一条用户消息，避免重复。
> 或者，更简单的做法：**将上下文放在最后一条 UserMessage 之前拼接**。

**最终消息结构推荐**：

```
SystemMessage: RAG 系统提示（已包含 context + 规则）
  └─ 历史消息（user/assistant 对）
  └─ UserMessage: 用户当前问题
```

即系统提示注入 context，历史消息 + 当前问题维持原有对话结构。
这样做的好处是模型理解对话流更自然，且不需要修改历史消息加载逻辑。

---

## 6. 阶段四：ChatServiceImpl 接线

### 6.1 改造点

改造 `ChatServiceImpl.send()` 和 `sendStream()` 中的第 7 步（当前标记为 TODO）。

**改造前**：
```java
// 7. 加载历史消息并组装 Prompt（TODO: 上下文拼装在后续模块完成）
List<ChatMessage> history = loadHistoryMessages(session.getId());
List<Message> promptMessages = buildPromptMessages(history, dto.getSystemPrompt());
```

**改造后**：
```java
// 7. 上下文组装 + Prompt 构建
AssembledContext context = contextAssemblyService.assemble(
    searchResult != null ? searchResult.getChunks() : List.of(),
    dto.getContent()
);
List<ChatMessage> history = loadHistoryMessages(session.getId());
List<Message> promptMessages = ragPromptService.buildRagMessages(
    dto.getSystemPrompt(), context, dto.getContent(), history
);

log.info("[ChatService] 上下文组装完成: chunks={}, tokens={}, costMs(含检索)={}",
    context.getChunkCount(), context.getTotalTokens(),
    searchResult != null ? searchResult.getCostMs() : 0);
```

### 6.2 引用信息返回

#### 6.2.1 同步接口（`ChatMessage`）

在 `ChatMessage` entity 中增加字段，或通过 VO 返回时附加 citations。

**方案**：扩展 `ChatMessage` entity，新增 `citations` 字段（JSON 存储），或保持 entity 不变，在 VO/DTO 中附加。

> 推荐：`ChatMessage` entity 保持不变（历史消息不需要存引用），在同步响应的 VO 中附加 citations 列表。

但由于现有 `send()` 接口直接返回 `ChatMessage` entity，需要：
1. 新增 `ChatMessageVO` 作为对外响应对象
2. 在 VO 中增加 `citations` 字段
3. Controller 层做转换

#### 6.2.2 流式接口（SSE）

在 `done` 事件中附加引用信息。

**扩展 `ChatStreamEvent`**：

```java
// 新增字段
@Schema(description = "引用来源列表（RAG 检索命中的文档片段）")
private List<CitationVO> citations;

// done 工厂方法增加 citations 参数
public static ChatStreamEvent done(String sessionId, Long aiMsgId, String fullContent,
                                   String finishReason, String modelName, Integer index,
                                   List<CitationVO> citations) { ... }
```

**新增 `CitationVO`**（位于 `chat.vo` 包，或复用 `rag.context.Citation` 转 VO）：

```java
@Data
@Builder
@Schema(description = "引用来源")
public class CitationVO {
    @Schema(description = "引用编号（对应回答中的 [n]）")
    private Integer index;
    @Schema(description = "文档ID")
    private Long documentId;
    @Schema(description = "文档名称")
    private String documentName;
    @Schema(description = "章节标题")
    private String chapterTitle;
    @Schema(description = "片段内容")
    private String content;
    @Schema(description = "相关性分数")
    private Double score;
}
```

### 6.3 历史消息去重处理

**问题**：`loadHistoryMessages()` 加载了全部历史，包括刚刚插入的当前用户消息。
而系统提示已经包含了当前问题（`{question}`），再在历史末尾出现一次会导致重复。

**解决方案选择**：

| 方案 | 说明 | 优点 | 缺点 |
|------|------|------|------|
| A. 系统提示仅注入规则 + context，当前问题仍走 UserMessage | 系统提示是纯规则说明 + 参考文档，当前问题由 UserMessage 承载 | 对话结构最自然，历史消息无需改动 | 系统提示与当前问题分离，模型可能忽略上下文 |
| B. 历史消息排除最后一条用户消息 | loadHistory 时排除 message_index 最大的 user 消息 | 改动小 | 逻辑略绕，需注意边界 |
| C. 上下文拼在最后一条 UserMessage 之前 | 修改 buildPromptMessages，将 context 作为前缀注入最后一条 UserMessage | 符合常见 RAG 模式 | 改动稍大 |

**推荐方案 A**：最简单且符合对话规范。

**最终系统提示结构**：

```
SystemMessage:
  "你是一个专业的文档问答助手，请基于提供的参考文档片段回答用户问题。
   【回答规则】
   ...
   【参考文档】
   [1] 《文档.pdf》- 章节
   内容...
   ..."

UserMessage: 用户问题
AssistantMessage: 历史回答
...
UserMessage: 当前用户问题
```

这样系统提示中包含了参考文档，模型在回答每条用户消息时都会参考。
历史对话流保持完整，不需要去重。

### 6.4 Token 预算与上下文窗口

DeepSeek V4 Flash 支持 128K context window，3000 tokens 的上下文预算非常宽裕。
为安全起见，仍做 token 预算控制：

- **上下文预算**：`ai.rag.context.max-tokens=3000`（默认）
- **系统提示 overhead**：约 200 tokens（模板固定部分）
- **历史消息**：按实际计算（当前无截断，后续可加）
- **回答预算**：至少 4K tokens 留给生成

当前阶段先用固定 3000 token 上限，后续根据需要加入历史消息截断。

---

## 7. 模块代码结构

### 7.1 新增文件清单

```
src/main/java/org/grayray/aiassistant/rag/
├── context/
│   ├── ContextAssemblyService.java       // 上下文组装接口
│   ├── ContextAssemblyServiceImpl.java   // 默认实现
│   ├── AssembledContext.java             // 组装结果
│   ├── Citation.java                     // 引用信息
│   ├── ContextFormat.java                // 组装格式枚举
│   ├── ContextTruncationResult.java      // 截断结果
│   └── ContextProperties.java            // 配置属性
└── prompt/
    ├── RagPromptService.java             // RAG Prompt 构建服务
    └── RagPromptTemplateLoader.java      // 模板加载（可选，可直接用 @Value）

src/main/java/org/grayray/aiassistant/chat/
├── vo/
│   └── CitationVO.java                   // 前端引用 VO
└── service/impl/
    └── ChatServiceImpl.java              // 改造：接入上下文 + Prompt

src/main/resources/prompts/rag/
├── rag-system.st                         // RAG 系统提示模板
└── fallback-system.st                    // 无上下文降级模板
```

### 7.2 配置项汇总

```yaml
ai:
  rag:
    context:
      max-chunks: 5           # 最终上下文最大片段数
      max-tokens: 3000        # 上下文 token 预算上限
      min-score: null         # 额外分数过滤（null 表示不过滤）
      format: numbered        # 组装格式：numbered / markdown / plain
```

---

## 8. 与现有模块的集成关系

### 8.1 与 Rerank 的集成

- Rerank 模块保持不变，输出 `List<RerankedChunk>` → 在 `ChatServiceImpl` 中已转成 `List<RetrievedChunk>`
- Context 模块只消费 `List<RetrievedChunk>`，不关心是 rerank 过的还是原始检索结果
- Rerank 的 `topM`（默认 4）与 Context 的 `max-chunks`（默认 5）：
  - 当 rerank 启用时，rerank 已截断到 topM=4，context 层的 max-chunks=5 不会生效
  - 当 rerank 关闭时，检索层 finalTopN=6，context 层截断到 max-chunks=5
  - 两层截断形成保护：检索层多召回一些（给 rerank 更多候选），context 层做最终把关

### 8.2 与 VectorSearch 的集成

- 向量检索层 `finalTopN=6` —— 给 rerank 或 context 留有挑选余地
- Context 层 `max-chunks=5` —— 最终送入上下文的上限

### 8.3 与 ChatServiceImpl 的集成

- 同步接口 `send()` 和流式接口 `sendStream()` 各有一处 TODO，都在第 7 步
- 两处改造逻辑完全一致，可抽取私有方法复用
- 引用返回方式不同：同步通过响应 VO，流式通过 done 事件

---

## 9. 异常与降级策略

| 场景 | 策略 |
|------|------|
| 检索结果为空 | 使用 fallback-system 模板，LLM 直接回答（等价于无 RAG） |
| Context 组装异常 | 捕获异常，降级为无上下文模式，记录 ERROR 日志 |
| Token 预算异常（chunk.tokenCount 为 null） | 按字符数估算 token（中文 ~2 字/token，英文 ~4 字符/token），兜底估算 |
| Prompt 模板加载失败 | 启动时抛出异常（启动失败），避免运行时静默降级 |
| 引用解析失败（前端） | 不影响回答展示，仅隐藏引用悬浮 |

**核心原则**：上下文组装失败不应阻断对话，降级为纯聊天模式即可。

---

## 10. 日志与可观测性

### 10.1 关键日志

```
[ContextAssembly] chunks=5, totalTokens=2847, format=NUMBERED, truncateReason=TOKEN, dropped=1
[RagPrompt] contextUsed=true, template=rag-system, historyMessages=6, totalTokens~=3200
```

### 10.2 指标

| 指标 | 说明 |
|------|------|
| `rag.context.chunk_count.avg` | 平均上下文片段数 |
| `rag.context.token_count.avg` | 平均上下文 token 数 |
| `rag.context.empty_rate` | 空上下文占比 |
| `rag.context.truncate_rate` | 触发截断的比例 |
| `rag.prompt.template_used` | 模板使用分布（rag/fallback/custom） |

---

## 11. 测试计划

### 11.1 单元测试

| 测试类 | 覆盖点 |
|--------|--------|
| `ContextAssemblyServiceTest` | 空列表、单片段、多片段、不同格式、章节标题缺失场景 |
| `RagPromptServiceTest` | 有上下文、无上下文、用户自定义 systemPrompt、历史消息拼接顺序 |
| `ContextTruncationTest` | token 预算截断、条数截断、分数过滤、边界条件 |

### 11.2 集成测试

| 场景 | 验证点 |
|------|--------|
| 同步对话 + 有检索结果 | 回答基于上下文、引用标注正确、citations 返回正确 |
| 流式对话 + 有检索结果 | 流式输出正常、done 事件带 citations |
| 对话 + 无检索结果 | 走 fallback 模板、回答正常、无 citations |
| Rerank 启用 + 上下文 | rerank 后的顺序正确传入 context |
| 历史多轮对话 | 上下文注入正确、历史消息完整 |

---

## 12. 迭代计划

| 阶段 | 内容 | 价值 |
|------|------|------|
| **P0（本次实现）** | Context 组装服务 + RAG Prompt 模板 + ChatServiceImpl 接线 + citations 返回 | 打通 RAG 全链路，回答基于文档 |
| P1 | 历史消息 token 截断（对话轮数过多时裁剪历史） | 长对话场景下避免超 token 限制 |
| P2 | 引用解析与高亮（前端解析 [n]，悬浮展示片段详情） | 用户体验提升 |
| P3 | 动态 TopN（根据问题复杂度自适应片段数） + Prompt 版本管理 | 精细化控制 |
| P4 | 多语言 Prompt 模板 + 不同场景（摘要/对比/翻译）专用模板 | 场景化能力 |

---

## 13. 附录：关键文件路径

| 文件 | 说明 |
|------|------|
| `rag/context/ContextAssemblyService.java` | 上下文组装接口（新增） |
| `rag/context/ContextAssemblyServiceImpl.java` | 上下文组装实现（新增） |
| `rag/context/AssembledContext.java` | 组装结果 DTO（新增） |
| `rag/context/Citation.java` | 引用信息（新增） |
| `rag/context/ContextProperties.java` | 上下文配置（新增） |
| `rag/prompt/RagPromptService.java` | RAG Prompt 服务（新增） |
| `chat/service/impl/ChatServiceImpl.java` | 接线改造（已有，修改） |
| `chat/vo/ChatStreamEvent.java` | 增加 citations 字段（已有，修改） |
| `chat/vo/CitationVO.java` | 引用 VO（新增） |
| `prompts/rag/rag-system.st` | RAG 系统提示模板（新增） |
| `prompts/rag/fallback-system.st` | 无上下文降级模板（新增） |
| `application.yaml` | 增加 ai.rag.context 配置（已有，修改） |
