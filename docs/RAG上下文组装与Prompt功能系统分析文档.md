# RAG 上下文组装与 Prompt 功能系统分析文档

> 版本：v1.0
> 日期：2026-08-09
> 项目：ai-assistant（Spring Boot 3.4 + Spring AI 1.1）
> 范围：Rerank → TopN 截断 → Context 组装 → Prompt → 引用返回 全链路分析

---

## 1. 系统概述

### 1.1 系统定位

本模块是 RAG（Retrieval-Augmented Generation）管线的**生成层核心**，承担着将检索结果转化为 LLM 可消费的 Prompt，并将引用来源反馈给前端的职责。它位于 RAG 链路的最末端，上游对接向量检索与 Rerank，下游对接 LLM 生成。

### 1.2 解决的核心问题

| 问题 | 解决方案 |
|------|----------|
| 检索片段数量/Token 超出上下文窗口 | 三层截断策略（分数过滤 → 条数限制 → Token 预算贪心截断） |
| 检索结果无法直接注入 LLM | 结构化上下文组装（编号引用 + 元数据 + 前导说明） |
| LLM 回答无来源依据 | RAG 系统提示模板 + 引用标注规范 + citations 数据结构 |
| 检索为空时回答质量下降 | Fallback 系统提示模板，降级为通用对话 |
| 前端无法展示引用来源 | 同步接口通过 `ChatSendResult.citations` 返回，流式接口通过 `done` 事件携带 |
| 上下文组装失败导致对话中断 | 异常捕获 + 空上下文降级，保证主流程可用 |

### 1.3 在系统架构中的位置

```
┌─────────────────────────────────────────────────────────────────┐
│                        ai-assistant 系统                         │
├─────────────────────────────────────────────────────────────────┤
│  Chat Controller (REST + SSE)                                    │
│       │                                                           │
│  ChatService (send / sendStream)                                  │
│       │                                                           │
│  ┌────┴─────────────────────────────────────────────────────┐    │
│  │                    RAG Pipeline                          │    │
│  │  Query Router → Vector Search → Rerank → 【本模块】 → LLM │    │
│  └──────────────────────────────────────────────────────────┘    │
│       │                                                           │
│  DeepSeek ChatModel / StreamingChatModel                          │
└─────────────────────────────────────────────────────────────────┘
```

---

## 2. 系统架构

### 2.1 总体架构图

```
                        ┌──────────────────────────┐
                        │   ChatServiceImpl        │
                        │  (send / sendStream)     │
                        └──────────┬───────────────┘
                                   │
                ┌──────────────────┼──────────────────┐
                │                  │                  │
                ▼                  ▼                  ▼
    ┌────────────────────┐ ┌───────────────┐ ┌──────────────────┐
    │ ContextAssembly    │ │ RagPrompt     │ │ Citation → VO    │
    │ Service            │ │ Service       │ │ 转换             │
    │                    │ │               │ │                  │
    │ ① minScore 过滤    │ │ ① 模板选择    │ │ 同步：ChatSendResult
    │ ② maxChunks 截断   │ │    - rag-system│    .citations     │
    │ ③ maxTokens 贪心   │ │    - fallback │ 流式：done 事件    │
    │ ④ 格式组装         │ │ ② 消息拼装    │ │    .citations     │
    └─────────┬──────────┘ └───────┬───────┘ └──────────────────┘
              │                     │
              ▼                     ▼
    ┌────────────────────┐ ┌──────────────────────┐
    │ AssembledContext   │ │ Spring AI Message    │
    │  - text            │ │  - SystemMessage     │
    │  - citations       │ │  - UserMessage       │
    │  - totalTokens     │ │  - AssistantMessage  │
    │  - chunkCount      │ │  (历史 + 当前)       │
    └────────────────────┘ └──────────────────────┘
```

### 2.2 模块划分

| 模块 | 包路径 | 核心类 | 职责 |
|------|--------|--------|------|
| **上下文组装模块** | `rag.context` | `ContextAssemblyService` (接口) | 定义上下文组装契约 |
| | | `ContextAssemblyServiceImpl` (实现) | 截断 + 格式化的核心逻辑 |
| | | `AssembledContext` | 组装结果数据结构 |
| | | `Citation` | 引用信息数据结构 |
| | | `ContextTruncationResult` | 截断结果数据结构 |
| | | `ContextFormat` (枚举) | 组装格式：NUMBERED / MARKDOWN / PLAIN |
| | | `ContextProperties` | 配置属性绑定 |
| **Prompt 构建模块** | `rag.prompt` | `RagPromptService` | 模板加载 + Prompt 消息构建 |
| **Chat 集成层** | `chat.service.impl` | `ChatServiceImpl` | 上下文组装与 Prompt 的调用接线 |
| | `chat.model` | `ChatSendResult` | 同步响应结果（含 citations） |
| | `chat.vo` | `CitationVO` | 前端引用视图对象 |
| | | `ChatStreamEvent` | 流式事件（done 携带 citations） |
| | | `ChatMessageVO` | 消息 VO（含 citations 字段） |
| **模板资源** | `prompts/rag` | `rag-system.st` | RAG 系统提示模板 |
| | | `fallback-system.st` | 无上下文降级模板 |

### 2.3 数据流全景

```
List<RetrievedChunk>              // 输入：向量检索 / Rerank 后的片段列表
       │
       ▼
┌───────────────────────┐
│ ContextAssemblyService│
│  assemble(chunks, query) │
└───────────┬───────────┘
       │
       ├── 内部处理流程：
       │   1. minScore 过滤 → List<RetrievedChunk>
       │   2. maxChunks 截断 → List<RetrievedChunk>
       │   3. maxTokens 贪心截断 → ContextTruncationResult
       │   4. 按 format 组装文本 + 构建 citations
       │
       ▼
AssembledContext                   // 输出 1：上下文文本 + 引用列表 + 统计信息
       │
       ├───────────────┐
       │               │
       ▼               ▼
RagPromptService    toCitationVOs()
  buildRagMessages()    （ChatServiceImpl 内）
       │                   │
       ▼                   ▼
List<Message>        List<CitationVO>
（Spring AI 消息列表）  （前端视图对象）
       │
       ▼
Prompt → ChatModel.call() / stream()
```

---

## 3. 功能模块详细分析

### 3.1 TopN 截断功能

#### 3.1.1 功能描述

对上游传入的检索片段进行**三层递进式截断**，确保最终送入上下文的片段在质量、数量和 Token 预算上都满足约束。

#### 3.1.2 处理流程

```
输入：List<RetrievedChunk>（已按相关性降序）
   │
   ▼
① minScore 过滤（可选）
   条件：ai.rag.context.min-score 不为 null
   操作：过滤 score < minScore 的片段
   副作用：若有丢弃，truncateReason = "SCORE"
   │
   ▼
② maxChunks 条数截断
   条件：片段数 > max-chunks（默认 5）
   操作：保留前 max-chunks 条
   副作用：若有截断，truncateReason = "COUNT"
   │
   ▼
③ maxTokens Token 预算截断（贪心算法）
   条件：总 token 数 > max-tokens（默认 3000）
   操作：从第 1 条开始累加，超预算即停止
   特殊：单条超预算仍保留第一条（保证至少有一条上下文）
   副作用：若有截断，truncateReason = "TOKEN"
   │
   ▼
输出：ContextTruncationResult
  - chunks: 入选片段
  - totalTokens: 入选总 token 数
  - droppedCount: 丢弃数量
  - truncateReason: 截断原因
```

#### 3.1.3 Token 估算机制

当片段的 `tokenCount` 字段为 null 或 0 时，使用字符数兜底估算：

```java
// 公式：content.length() / 3
// 依据：中文约 2 字/token，英文约 4 字符/token，混合场景按 3 字符/token 粗估
return Math.max(1, content.length() / 3);
```

**设计考量**：这是一个保守估算（实际 token 数通常小于估算值），确保不会因低估而超出上下文窗口。

#### 3.1.4 截断优先级设计

三层截断按 **质量 → 数量 → Token** 顺序执行，优先级体现在：

- **分数过滤**优先：质量不达标的片段直接丢弃，不占用后续配额
- **条数截断**居中：控制整体数量，限制计算量
- **Token 截断**兜底：最终保障上下文窗口安全

每层截断都可能覆盖上一层的 `truncateReason`，最终记录**最严格**（最靠后）的触发原因。

### 3.2 上下文组装功能

#### 3.2.1 功能描述

将截断后的片段列表组装为**结构化、带引用编号、包含元数据**的上下文文本块，同时构建对应的引用信息列表。

#### 3.2.2 三种组装格式

| 格式 | 枚举值 | 适用场景 | 特点 |
|------|--------|----------|------|
| **编号引用**（默认） | `NUMBERED` | 通用问答，需要引用标注 | LLM 易学习、前端易解析、结构清晰 |
| **Markdown** | `MARKDOWN` | 直接展示上下文的场景 | 视觉层次好，适合渲染 |
| **纯文本** | `PLAIN` | 调试 / 极简场景 | 无 overhead，仅内容拼接 |

#### 3.2.3 编号引用格式（默认）详细结构

```
以下是检索到的参考文档片段，请基于这些内容回答问题。
回答时请在相关句子后使用 [n] 标注引用来源（n 为片段编号）。
如果参考内容不足以回答问题，请明确说明，不要编造信息。

[1] 《文档名称.pdf》- 第3章 章节标题
片段内容文本片段内容文本片段内容文本...

[2] 《另一文档.pdf》- 第1章 章节标题
片段内容文本...
```

**组成部分**：
1. **前导说明**（3 行）：告知 LLM 回答规则与引用标注方式
2. **编号标记** `[n]`：从 1 开始，与 `citations` 列表索引一一对应
3. **元数据行**：《文档名》 + 可选章节标题（`- ` 分隔）
4. **内容行**：片段完整文本
5. **空行分隔**：片段之间用空行分隔，提高可读性

#### 3.2.4 Markdown 格式结构

```markdown
### 参考文档

**[1] 文档名称.pdf · 章节标题**

> 内容第一行
> 内容第二行

**[2] 另一文档.pdf · 章节标题**

> 内容...
```

特点：使用 Markdown 引用块（`> ` 前缀），每一行都添加前缀。

#### 3.2.5 Citation 数据结构分析

```
Citation
├── index: int           // 引用编号（从1开始）
├── chunkId: String      // 片段唯一ID
├── documentId: Long     // 文档ID
├── documentName: String // 文档名称
├── chapterTitle: String // 章节标题（可空）
├── chapterIndex: Integer// 章节序号（可空）
├── chunkIndex: Integer  // 片段在文档中的序号
├── content: String      // 片段完整内容
└── score: double        // 相关性分数
```

**字段用途分析**：

| 字段 | 注入 Prompt | 前端展示 | 内部追踪 |
|------|:-----------:|:--------:|:--------:|
| index | ✅ | ✅（编号对应） | |
| chunkId | | | ✅（唯一标识） |
| documentId | | ✅（点击跳转文档） | ✅ |
| documentName | ✅（元数据行） | ✅（显示来源） | |
| chapterTitle | ✅（元数据行） | ✅（章节定位） | |
| chapterIndex | | | ✅（排序） |
| chunkIndex | | | ✅（定位片段位置） |
| content | ✅（主体内容） | ✅（悬浮展示） | |
| score | | ✅（相关度排序） | ✅（截断依据） |

### 3.3 Prompt 构建功能

#### 3.3.1 功能描述

根据上下文状态和用户配置，选择合适的系统提示模板，与历史消息一起组装为 Spring AI 的 `Message` 列表。

#### 3.3.2 模板体系

| 模板 | 文件 | 触发条件 | 内容 |
|------|------|----------|------|
| **RAG 系统提示** | `rag-system.st` | 有检索上下文 + 无自定义 systemPrompt | 角色设定 + 6条回答规则 + 参考文档注入 |
| **降级提示** | `fallback-system.st` | 无检索上下文 + 无自定义 systemPrompt | 通用助手设定 + 无文档提示 |
| **自定义提示** | 用户传入 | `dto.systemPrompt` 非空 | 用户自定义 + 有上下文时追加参考文档 |
| **默认提示** | 常量 `DEFAULT_SYSTEM_PROMPT` | fallback 模板加载失败 | "你是一个有帮助的AI助手。" |

#### 3.3.3 RAG 系统提示模板分析

模板内容（`rag-system.st`）：

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
```

**模板设计要点**：

1. **角色定位**：明确"文档问答助手"身份，限制回答范围
2. **规则结构化**：使用编号列表，LLM 更容易遵循
3. **引用规范**：明确定义 `[n]` 标注格式，与上下文组装格式一致
4. **防幻觉机制**：第 1、2 条规则直接约束 LLM 不编造信息
5. **优先级引导**：第 6 条引导模型优先引用高相关度片段
6. **占位符**：`{context}` 注入 `AssembledContext.text`（含前导说明 + 所有片段）

#### 3.3.4 消息构建策略（方案 A）

采用**系统提示注入上下文 + 历史消息保持完整**的方案：

```
[SystemMessage]
  角色设定 + 回答规则 + 参考文档（全部在 SystemMessage 中）
  ├─ 有上下文时：rag-system 模板渲染结果
  └─ 无上下文时：fallback-system 模板内容

[UserMessage]      ┐
[AssistantMessage] │ 历史消息（按时间顺序，完整保留）
[UserMessage]      │
[AssistantMessage] ┘
[UserMessage]      ← 当前用户问题
```

**方案 A 的优势**：
- 对话结构最自然，符合 LLM 对多轮对话的理解方式
- 历史消息无需修改，避免去重逻辑带来的边界问题
- 参考文档放在系统提示中，模型在所有回答中都能参考
- 实现简单，代码量小

**潜在风险**：
- 上下文内容在系统提示中，部分模型可能对系统提示的注意力弱于用户消息
- 多轮对话时，较早轮次的上下文可能被后续对话覆盖注意力

**缓解措施**：通过模板中的强调性规则（"只使用参考文档中的信息"）增强模型对系统提示中上下文的关注。

#### 3.3.5 自定义 systemPrompt 处理

当用户传入 `systemPrompt` 时，处理逻辑如下：

```java
if (userSystemPrompt 非空) {
    if (context 非空) {
        return userSystemPrompt + "\n\n【参考文档】\n" + context.getText();
    }
    return userSystemPrompt;
}
```

**设计考量**：
- 用户自定义提示优先：尊重业务方的人设/语气定制需求
- 有上下文时追加：不破坏用户提示的结构，将参考文档附加在末尾
- 使用 `【参考文档】` 标识：与内置模板风格一致，模型容易识别

#### 3.3.6 模板渲染机制

使用简单的 `{key}` 占位符替换，与 `AbstractQueryComponent` 保持风格一致：

```java
private String renderTemplate(String template, String... keyValues) {
    String result = template;
    for (int i = 0; i + 1 < keyValues.length; i += 2) {
        result = result.replace("{" + keyValues[i] + "}",
                keyValues[i + 1] == null ? "" : keyValues[i + 1]);
    }
    return result;
}
```

特点：
- 轻量级，无第三方依赖
- 与查询预处理模块（classify/rewrite/expand）风格统一
- 支持可变参数，灵活扩展占位符

### 3.4 ChatService 集成功能

#### 3.4.1 集成点分析

`ChatServiceImpl` 中有两处集成：`send()` 第 7 步和 `sendStream()` 第 7 步，逻辑完全一致。

**集成代码结构**：

```java
// 7. 上下文组装 + Prompt 构建
List<RetrievedChunk> chunks = (searchResult != null && !searchResult.isEmpty())
        ? searchResult.getChunks() : Collections.emptyList();
AssembledContext context;
try {
    context = contextAssemblyService.assemble(chunks, content);
} catch (Exception e) {
    // 降级为空上下文
    context = AssembledContext.empty(ContextFormat.NUMBERED);
}
List<ChatMessage> history = loadHistoryMessages(session.getId());
List<Message> promptMessages = ragPromptService.buildRagMessages(
        systemPrompt, context, content, history);
List<CitationVO> citations = toCitationVOs(context);
```

#### 3.4.2 异常防护层级

RAG 各阶段异常都不会阻断主对话流程，形成**多层防护**：

| 阶段 | 异常处理 | 降级结果 |
|------|----------|----------|
| Query Router | catch + warn | searchQueries = null，跳过检索 |
| 向量检索 | catch + warn | searchResult = null，无上下文 |
| Rerank | catch + warn | 保留原始检索顺序 |
| 上下文组装 | catch + error | 空上下文（`AssembledContext.empty()`） |
| Prompt 构建 | 启动时加载失败直接抛异常 | 启动失败，避免运行时静默降级 |

**核心原则**：上下文相关的失败都是"软失败"，降级为纯聊天模式；模板加载失败是"硬失败"，启动时暴露问题。

#### 3.4.3 引用返回方式

**同步接口**（`send()`）：
- 通过 `ChatSendResult.citations` 返回
- Controller 层将 `ChatMessage` + `citations` 转换为 `ChatMessageVO`

**流式接口**（`sendStream()`）：
- 通过 `done` 事件的 `citations` 字段返回
- 在流式生成完成后，与 `fullContent`、`finishReason` 一起发送
- 前端收到 done 事件后解析 citations 展示引用

#### 3.4.4 CitationVO 与 Citation 的关系

```
Citation（领域模型，rag.context 包）         CitationVO（视图模型，chat.vo 包）
├── index                                     ├── index
├── chunkId                                   ├── documentId
├── documentId                    ──────────► ├── documentName
├── documentName                              ├── chapterTitle
├── chapterTitle                              ├── content
├── chapterIndex                              └── score
├── chunkIndex
├── content
└── score
```

**VO 层裁剪**：去掉了 `chunkId`、`chapterIndex`、`chunkIndex` 等内部字段，只保留前端展示需要的信息。

**转换位置**：`ChatServiceImpl.toCitationVOs()` 私有方法，在 service 层完成转换。

---

## 4. 数据结构分析

### 4.1 核心数据结构关系图

```
RetrievedChunk                    AssembledContext
┌───────────────────┐            ┌──────────────────────────┐
│ chunkId           │            │ text: String             │
│ documentId        │            │ citations: List<Citation> │
│ documentName      │            │ totalTokens: int         │
│ chunkIndex        │            │ chunkCount: int          │
│ chapterIndex      │            │ format: ContextFormat    │
│ chapterTitle      │            │ truncateReason: String   │
│ content           │            │ droppedCount: int        │
│ tokenCount        │            └────────────┬─────────────┘
│ score             │                         │
│ matchedQuery      │                    ┌────▼─────┐
│ hitCount          │                    │ Citation │
└───────────────────┘                    └────┬─────┘
        │                                     │
        │  输入到 ContextAssemblyService       │
        │                                     │
        ▼                                     ▼
ContextTruncationResult                CitationVO
┌──────────────────────┐               ┌──────────────────┐
│ chunks: List<...>    │               │ index            │
│ totalTokens: int     │  内部中间结构  │ documentId       │
│ droppedCount: int    │               │ documentName     │
│ truncateReason: String│              │ chapterTitle     │
└──────────────────────┘               │ content          │
                                       │ score            │
                                       └──────────────────┘
```

### 4.2 数据流转表

| 数据对象 | 生产者 | 消费者 | 流向 |
|----------|--------|--------|------|
| `RetrievedChunk` | VectorSearchServiceImpl / Rerank | ContextAssemblyServiceImpl | 检索 → 上下文组装 |
| `ContextTruncationResult` | ContextAssemblyServiceImpl.truncate() | formatNumbered / formatMarkdown / formatPlain | 截断 → 格式化（内部流转） |
| `AssembledContext` | ContextAssemblyServiceImpl | RagPromptService + ChatServiceImpl | 上下文组装 → Prompt 构建 + 引用转换 |
| `Citation` | ContextAssemblyServiceImpl.buildCitation() | ChatServiceImpl.toCitationVOs() | 组装 → VO 转换 |
| `List<Message>` | RagPromptService.buildRagMessages() | DeepSeek ChatModel | Prompt 构建 → LLM 生成 |
| `CitationVO` | ChatServiceImpl.toCitationVOs() | 前端（同步响应 / SSE done） | Service → 前端 |

### 4.3 空值处理规范

| 对象 | 空状态 | 判断方法 |
|------|--------|----------|
| `RetrievedChunk` 列表 | null 或 empty | `searchResult != null && !searchResult.isEmpty()` |
| `AssembledContext` | 空片段 | `context.isEmpty()`（chunkCount == 0 或 citations 为空） |
| `Citation` 列表 | empty list | `context.getCitations() == null \|\| context.getCitations().isEmpty()` |
| `CitationVO` 列表 | empty list | Collections.emptyList()（永远不为 null） |

**设计原则**：对外返回的集合永远不为 null（使用 `Collections.emptyList()`），避免 NPE。

---

## 5. 配置分析

### 5.1 配置项清单

配置前缀：`ai.rag.context`

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `max-chunks` | int | 5 | 最终送入上下文的最大片段数（硬上限） |
| `max-tokens` | int | 3000 | 上下文 token 预算上限（贪心截断） |
| `min-score` | Double | null | 额外分数阈值（null 表示不过滤） |
| `format` | String | "numbered" | 组装格式：numbered / markdown / plain |

### 5.2 默认值分析

**max-chunks = 5**：
- 与 Rerank 的 `topM = 4`（略大，给 rerank 关闭时留余地）
- 与向量检索的 `finalTopN = 6`（检索多召回 1 条作为缓冲）
- 形成"检索 6 条 → 最终 5 条"的双层保护

**max-tokens = 3000**：
- DeepSeek V4 Flash 支持 128K 上下文窗口
- 3000 token 约占窗口的 2.3%，非常宽裕
- 预留充足空间给历史消息 + 系统提示 + 生成内容
- 保守设置，后续可根据实际情况上调

**min-score = null**：
- 默认不做额外分数过滤
- 依赖检索层 / rerank 层的 minScore 配置
- 避免双重过滤导致召回不足

**format = numbered**：
- LLM 最容易学习的引用标注格式
- 前端解析 `[n]` 做悬浮展示的实现成本最低
- 与 RAG 系统提示模板中的引用规范一致

### 5.3 与上游配置的协同

```
向量检索层：finalTopN = 6
        │
        ▼
Rerank 层：topM = 4（默认关闭时不生效）
        │
        ▼
Context 层：max-chunks = 5  ←── 最终把关
            max-tokens = 3000
            min-score = null
```

**协同逻辑**：
- 检索层多召回一些（6 条），给 rerank 或 context 层留有挑选余地
- Rerank 开启时，4 条结果进入 context，context 层的 max-chunks=5 不触发
- Rerank 关闭时，6 条结果进入 context，context 层截断到 5 条
- 两层截断形成漏斗形保护，层层收敛

---

## 6. 异常处理与降级策略

### 6.1 异常分类矩阵

| 异常场景 | 发生位置 | 处理方式 | 影响范围 | 用户感知 |
|----------|----------|----------|----------|----------|
| 检索结果为空 | ChatServiceImpl | 使用 fallback 模板 | 无引用、回答不基于文档 | 正常回答，无来源标注 |
| 上下文组装异常 | ContextAssemblyServiceImpl.assemble() | catch + 返回空上下文 | 同上 | 同上 |
| Token 估算异常 | estimateTokens() | 按字符数 / 3 兜底 | token 估算略保守 | 无感 |
| Prompt 模板加载失败 | RagPromptService.init() | 抛 IllegalStateException | 应用启动失败 | 启动报错 |
| 格式配置值非法 | ContextFormat.fromString() | 默认返回 NUMBERED | 格式降级为默认 | 无感 |
| 历史消息角色未知 | RagPromptService.buildRagMessages() | skip 跳过 | 该条消息丢失 | 轻微（边界情况） |
| citation 转换异常 | toCitationVOs() | 前置 null 检查 | 返回空列表 | 无引用展示 |

### 6.2 降级链路

```
正常流程：检索 → Rerank → 上下文组装 → RAG Prompt → 基于文档回答
           │        │         │             │
           ▼        ▼         ▼             ▼
降级 1：  空    →  跳过  →  空上下文  →  Fallback Prompt → 通用回答
         │
         └── 任一环节失败都逐级降级，最终保证对话不中断
```

### 6.3 关键异常处理代码分析

**上下文组装异常（两层防护）**：

```java
// 第一层：在 ContextAssemblyServiceImpl 内部
try {
    // 截断 + 组装逻辑
} catch (Exception e) {
    log.error("[ContextAssembly] 上下文组装异常，降级为空上下文", e);
    return AssembledContext.empty(format);
}

// 第二层：在 ChatServiceImpl 调用处（冗余防护）
try {
    context = contextAssemblyService.assemble(chunks, content);
} catch (Exception e) {
    log.error("[ChatService] 上下文组装异常，降级为空上下文", e);
    context = AssembledContext.empty(ContextFormat.NUMBERED);
}
```

**设计考量**：两层 try-catch 形成冗余防护，即使 Service 层漏了捕获，调用方也能兜底。这是一种防御性编程实践。

---

## 7. 性能与可观测性

### 7.1 性能特征

| 操作 | 时间复杂度 | 空间复杂度 | 说明 |
|------|-----------|-----------|------|
| minScore 过滤 | O(n) | O(n) | n = 输入片段数 |
| maxChunks 截断 | O(1) | O(1) | 直接 subList |
| maxTokens 截断 | O(k) | O(1) | k = 截断后片段数（≤ maxChunks） |
| 上下文组装（文本拼接） | O(m) | O(m) | m = 所有片段总字符数 |
| Prompt 构建 | O(h) | O(h) | h = 历史消息数 |

总体性能非常高，主要耗时在上游的检索和 Rerank，本模块是 CPU 密集型的内存操作，通常在毫秒级完成。

### 7.2 日志埋点

| 位置 | 日志级别 | 格式 | 关键字段 |
|------|----------|------|----------|
| 上下文组装完成 | INFO | `[ContextAssembly] chunks={}, totalTokens={}, format={}, truncateReason={}, dropped={}` | 片段数、token数、格式、截断原因、丢弃数 |
| 截断后无片段 | INFO | `[ContextAssembly] 截断后无片段可用: reason={}` | 截断原因 |
| 上下文组装异常 | ERROR | `[ContextAssembly] 上下文组装异常，降级为空上下文: {}` | 异常消息 |
| Prompt 模板加载完成 | INFO | `[RagPrompt] 模板加载完成: rag-system={}字, fallback-system={}字` | 模板字符数 |
| Prompt 构建完成 | DEBUG | `[RagPrompt] 构建完成: contextUsed={}, template={}, totalMessages={}` | 是否有上下文、模板名、消息总数 |
| ChatService 集成 | INFO | `[ChatService] 上下文组装完成: chunks={}, tokens={}, costMs(含检索)={}` | 片段数、token数、检索耗时 |

### 7.3 可观测性指标（设计值）

> 以下为设计文档中规划的指标，当前代码中已完成日志埋点，指标采集可后续通过 Micrometer 接入。

| 指标 | 类型 | 说明 |
|------|------|------|
| `rag.context.chunk_count.avg` | Gauge | 平均上下文片段数 |
| `rag.context.token_count.avg` | Gauge | 平均上下文 token 数 |
| `rag.context.empty_rate` | Rate | 空上下文占比 |
| `rag.context.truncate_rate` | Rate | 触发截断的比例 |
| `rag.prompt.template_used` | Counter | 模板使用分布（rag/fallback/custom） |

---

## 8. 设计模式与架构风格

### 8.1 设计模式应用

| 模式 | 应用位置 | 体现 |
|------|----------|------|
| **策略模式** | 上下文组装格式 | `ContextFormat` 枚举 + switch 分发三种格式（NUMBERED/MARKDOWN/PLAIN） |
| **模板方法模式** | Prompt 构建 | 固定的构建流程（系统提示 → 历史消息），模板内容可变 |
| **建造者模式** | 数据结构 | `@Builder` 注解，`AssembledContext`、`Citation`、`ContextTruncationResult` 等 |
| **防御性编程** | 全链路 | 多层 try-catch、空值检查、兜底估算、默认值 |
| **DTO / VO 分层** | 数据流转 | RetrievedChunk（检索层） → Citation（领域层） → CitationVO（视图层） |

### 8.2 架构风格

1. **分层清晰**：检索层 → 组装层 → Prompt 层 → Chat 集成层，每层职责单一
2. **配置驱动**：通过 `ContextProperties` 外部化配置，运行时可调整
3. **失败容忍**：软失败降级，不阻断主流程
4. **接口与实现分离**：`ContextAssemblyService` 接口 + `ContextAssemblyServiceImpl` 实现
5. **风格统一**：模板加载、占位符替换与 `AbstractQueryComponent` 保持一致

---

## 9. 与上下游模块的接口契约

### 9.1 上游接口（输入）

**向量检索 / Rerank → 上下文组装**

输入类型：`List<RetrievedChunk>`

约束：
- 列表已按相关性分数降序排列（高相关度在前）
- 每个 chunk 的 `score` 字段有效（用于 minScore 过滤）
- `content` 不为 null（为 null 时按空字符串处理）
- `tokenCount` 可选（为 null 时按字符数估算）
- `documentName` 不为 null（为 null 时按空字符串处理）

### 9.2 下游接口（输出）

**上下文组装 → LLM**

输出类型：`org.springframework.ai.chat.messages.Message` 列表

消息结构：
```
[0] SystemMessage（系统提示 + 上下文）
[1..n-1] 历史 User/Assistant 消息
[n] UserMessage（当前问题）
```

**引用信息 → 前端**

同步响应：`ChatSendResult.citations: List<CitationVO>`
流式响应：`ChatStreamEvent.citations: List<CitationVO>`（done 事件中）

---

## 10. 限制与待改进

### 10.1 当前限制

| 限制项 | 说明 | 影响 |
|--------|------|------|
| **历史消息无截断** | 对话轮数过多时，历史消息可能占满上下文窗口 | 长对话可能触发 Token 超限错误 |
| **Token 估算粗略** | 使用字符数 / 3 估算，与实际 token 数有偏差 | 预算可能偏保守（浪费上下文空间） |
| **无动态 TopN** | 固定 max-chunks，不根据问题复杂度自适应 | 简单问题也加载 5 条，复杂问题可能不够 |
| **静态模板** | Prompt 模板写在资源文件中，修改需重启 | 无法运行时调优 |
| **引用无解析** | 仅返回 citations 列表，不解析回答中的 `[n]` 标注 | 前端需自行解析（或仅在末尾展示列表） |
| **无多语言模板** | 只有中文模板 | 英文回答时 prompt 为中文，可能影响质量 |
| **单条超预算仍保留** | 当单条片段 token 数 > maxTokens 时，仍保留该条 | 可能略微超出预算（但保证有内容） |

### 10.2 后续迭代方向

参考设计文档的迭代计划：

| 阶段 | 内容 | 价值 |
|------|------|------|
| P1 | 历史消息 Token 截断 | 长对话场景下避免超 Token 限制 |
| P2 | 引用解析与高亮（前端） | 用户体验提升 |
| P3 | 动态 TopN + Prompt 版本管理 | 精细化控制 |
| P4 | 多语言模板 + 场景化模板 | 场景化能力 |

---

## 11. 测试要点分析

### 11.1 单元测试覆盖建议

| 测试类 | 核心测试场景 |
|--------|-------------|
| `ContextAssemblyServiceImplTest` | 空列表、单片段、多片段、三种格式、章节标题缺失、null 字段安全 |
| `ContextTruncationTest` | minScore 过滤、maxChunks 截断、maxTokens 贪心截断、单条超预算、全部超预算、边界值 0/1 |
| `RagPromptServiceTest` | 有上下文用 rag 模板、无上下文用 fallback、自定义 systemPrompt、自定义 + 有上下文追加、历史消息角色转换、空历史 |
| `ContextFormatTest` | 大小写不敏感、null/空值默认、非法值默认 |
| `AssembledContextTest` | isEmpty() 判断、empty() 工厂方法 |

### 11.2 集成测试覆盖建议

| 场景 | 验证点 |
|------|--------|
| 同步对话 + 有检索结果 | 回答基于上下文、citations 返回正确、数量正确 |
| 流式对话 + 有检索结果 | 流式输出正常、done 事件带 citations |
| 对话 + 无检索结果 | 走 fallback 模板、回答正常、citations 为空列表 |
| Rerank 启用 + 上下文 | rerank 后的分数传入 context、顺序正确 |
| 历史多轮对话 | 上下文注入正确、历史消息完整保留 |
| 上下文组装异常 | 降级为纯聊天、回答正常、ERROR 日志记录 |
| 自定义 systemPrompt | 用户提示生效、有上下文时追加参考文档 |

---

## 12. 关键文件清单

### 12.1 新增文件

```
src/main/java/org/grayray/aiassistant/rag/context/
├── ContextAssemblyService.java       // 上下文组装接口
├── ContextAssemblyServiceImpl.java   // 默认实现（截断 + 格式化）
├── AssembledContext.java             // 组装结果 DTO
├── Citation.java                     // 引用信息
├── ContextFormat.java                // 格式枚举
├── ContextTruncationResult.java      // 截断结果
└── ContextProperties.java            // 配置属性绑定

src/main/java/org/grayray/aiassistant/rag/prompt/
└── RagPromptService.java             // RAG Prompt 构建服务

src/main/java/org/grayray/aiassistant/chat/
├── model/ChatSendResult.java         // 同步发送结果（含 citations）
└── vo/CitationVO.java                // 前端引用 VO

src/main/resources/prompts/rag/
├── rag-system.st                     // RAG 系统提示模板
└── fallback-system.st                // 无上下文降级模板
```

### 12.2 修改文件

| 文件 | 修改内容 |
|------|----------|
| `chat/service/impl/ChatServiceImpl.java` | 第 7 步接入上下文组装 + Prompt 构建 + citations 转换 |
| `chat/vo/ChatStreamEvent.java` | 增加 citations 字段 + 重载 done() 工厂方法 |
| `chat/vo/ChatMessageVO.java` | 增加 citations 字段 |
| `application.yaml` | 增加 ai.rag.context 配置段 |

---

## 13. 总结

RAG 上下文组装与 Prompt 功能模块的设计和实现体现了以下核心思想：

1. **分层清晰、职责单一**：TopN 截断、上下文组装、Prompt 构建各司其职
2. **防御性编程、软失败降级**：任何环节失败都不阻断对话主流程
3. **配置驱动、灵活可调**：片段数、Token 预算、格式等均可配置
4. **前后端兼顾**：既考虑 LLM 消费的 Prompt 结构，也考虑前端展示的引用数据
5. **风格统一、易维护**：与现有查询预处理模块的模板机制、代码风格保持一致

该模块的实现标志着 RAG 管线从"能检索"到"能回答"的关键跨越，为后续的引用解析、动态调优、场景化模板等高级特性奠定了基础。
