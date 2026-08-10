# Query 路由系统分析文档

## 一、系统定位

Query Router 位于 **用户消息入库后、下游处理前** 的关键位置，作为 AI 助手对话链路中的"智能前置处理层"。它根据用户问题的语义特征，动态选择不同的预处理策略，将原始输入转化为标准化的查询列表，供下游模块（如检索、生成）消费。

> **核心理念**：让下游只需要消费 `RoutedQuery.queries` 字段，无需关心上游如何路由、重写或扩展。

---

## 二、系统架构

### 2.1 整体架构图

```mermaid
flowchart TD
    U[用户消息] --> C[ChatServiceImpl]
    C -->|save user msg| DB[(chat_message)]
    C -->|call route()| QR[QueryRouterService]
    QR -->|1. load history| DB
    QR -->|2. classify| QC[QueryClassifier]
    QC -->|LLM call| LLM[DeepSeek]
    QC -->|SIMPLE| QS[透传原始 query]
    QC -->|CONTEXTUAL| QRw[QueryRewriter]
    QRw -->|LLM call| LLM
    QC -->|COMPLEX| QE[QueryExpander]
    QE -->|LLM call| LLM
    QS --> OUT[RoutedQuery]
    QRw --> OUT
    QE --> OUT
    OUT -->|queries list| C
    C -->|generate response| LLM
```

### 2.2 类层次结构

```
org.grayray.aiassistant.service
└── QueryRouterService        ← 对外接口（唯一入口）

org.grayray.aiassistant.service.impl
├── QueryRouterServiceImpl    ← 主实现（编排调度）
├── AbstractQueryComponent    ← LLM 调用基类（模板渲染、JSON 解析）
├── QueryClassifier           ← 包私有 · 分类器
├── QueryRewriter             ← 包私有 · 重写器
└── QueryExpander             ← 包私有 · 扩展器
```

> **设计要点**：三个组件（Classifier/Rewriter/Expander）均为 **包私有 (package-private)**，仅 `QueryRouterServiceImpl` 使用，对外完全隐藏实现细节。

### 2.3 数据模型（BO 层）

| 类名 | 作用 | 关键字段 |
|---|---|---|
| `RoutedQuery` | 路由统一输出 | `type`, `originalQuery`, `queries`, `routeReason`, `sessionId`, `userId` |
| `QueryRouteResult` | 分类器输出 | `type` (simple/contextual/complex), `reason` |
| `ExpansionResult` | 扩展器输出 | `queries` (列表), `reason` |
| `RewriteResult` | 重写器输出（未直接使用） | `rewrittenQuery` |
| `QueryType` (枚举) | 查询类型 | `SIMPLE`, `CONTEXTUAL`, `COMPLEX` |

---

## 三、核心处理流程

### 3.1 路由主流程

```mermaid
flowchart TD
    A[route sessionId, userId, originalQuery] --> B[加载最近 5 轮历史]
    B --> C{历史为空?}
    C -->|是 - 首轮对话| S1[type=SIMPLE, queries=[original]]
    C -->|否| D[调用 Classifier 分类]
    D --> E{分类成功?}
    E -->|否 - 降级| F[type=SIMPLE, queries=[original], reason=降级原因]
    E -->|是| G{QueryType}
    G -->|SIMPLE| H[queries=[original]]
    G -->|CONTEXTUAL| I[调用 Rewriter 重写]
    I --> J{重写成功?}
    J -->|是| K[queries=[rewritten]]
    J -->|否 - 回退| L[queries=[original]]
    G -->|COMPLEX| M[调用 Expander 扩展]
    M --> N{扩展成功且非空?}
    N -->|是| O[queries=[q1, q2, q3, q4]]
    N -->|否 - 回退| L
    S1 --> P[构建 RoutedQuery, 日志记录]
    F --> P
    H --> P
    K --> P
    L --> P
    O --> P
```

### 3.2 三种路由类型

| 类型 | 判定条件 | 处理方式 | 输出 queries 数量 |
|---|---|---|---|
| **SIMPLE** | 语义完整、独立可理解、不依赖历史 | 直接透传 | 1 条 |
| **CONTEXTUAL** | 包含代词/省略/指代，依赖历史 | 调用 Rewriter 重写为自包含问题 | 1 条 |
| **COMPLEX** | 宽泛、模糊、多意图 | 调用 Expander 拆解为 2~4 个子查询 | 2~4 条 |

---

## 四、组件详解

### 4.1 QueryRouterServiceImpl（主调度）

**文件**：`service/impl/QueryRouterServiceImpl.java`

**职责**：
- 加载并格式化历史对话
- 编排分类 → 分发 → 输出的完整流程
- 统一管理降级策略
- 日志埋点与耗时统计

**关键常量**：
- `RECENT_HISTORY_ROUNDS = 5` — 加载最近 5 轮对话用于上下文理解

**历史加载策略**：
- 查询 `chat_message` 表，按 `message_index` 倒序
- 取 `rounds * 2 + 1` 条（多 1 条是因为要跳过刚入库的当前用户消息）
- 跳过第 1 条（最新的用户消息），剩余反转成升序
- 仅保留 `user` / `assistant` 角色

**降级机制**：
| 失败场景 | 降级行为 |
|---|---|
| 分类器调用失败 | 降级为 SIMPLE，返回原 query |
| 重写器调用失败 | 类型仍记为 CONTEXTUAL，queries 回退到原 query |
| 扩展器返回空列表 | 类型仍记为 COMPLEX，queries 回退到原 query |
| 扩展器调用失败 | 类型仍记为 COMPLEX，queries 回退到原 query |

### 4.2 QueryClassifier（查询分类器）

**文件**：`service/impl/QueryClassifier.java`

**Prompt 模板**：`prompts/query/classify.st`

**核心逻辑**：
1. 渲染模板：填充 `{recentHistory}` 和 `{currentQuery}`
2. 调用 DeepSeek LLM（`temperature = 0.0`，确保确定性）
3. 解析返回的 JSON → `QueryRouteResult`
4. 首次解析失败 → 自动重试 **1 次**
5. 仍失败 → 抛出异常，由上层降级

**Prompt 设计**：
```
你是一个查询分类器。根据用户当前问题及对话历史，判断问题属于以下三类之一：
1. simple：语义完整，独立可理解
2. contextual：包含代词/省略，依赖历史
3. complex：复杂/模糊/多意图，需多角度回答
```

### 4.3 QueryRewriter（查询重写器）

**文件**：`service/impl/QueryRewriter.java`

**Prompt 模板**：`prompts/query/rewrite.st`

**核心逻辑**：
1. 渲染模板：填充 `{recentHistory}` 和 `{currentQuery}`
2. 调用 DeepSeek LLM（`temperature = 0.0`）
3. 剥离结果两端的引号（支持 `""`、`''`、`""`）
4. **结果校验**：空结果 / 超过 200 字符 → 抛出异常，上层回退

**重写规则**（来自 Prompt）：
- 替换所有代词（它/这个/那个/他/她/上述/上文 等）为具体实体
- 补全省略的主语/宾语
- 保持原问题意图，不添加额外信息

### 4.4 QueryExpander（查询扩展器）

**文件**：`service/impl/QueryExpander.java`

**Prompt 模板**：`prompts/query/expand.st`

**核心逻辑**：
1. 渲染模板：填充 `{currentQuery}`
2. 调用 DeepSeek LLM（`temperature = 0.3`，略高以增加多样性）
3. 解析返回的 JSON → `ExpansionResult`
4. 首次失败 → 重试 **1 次**
5. 结果清洗：过滤空串 / 去重 / 超过 6 条截断

**关键常量**：
- `MAX_QUERIES = 6` — 子查询上限（Prompt 要求 2~4 条，代码允许最多 6 条）

**Prompt 设计**：
```
将宽泛、模糊或多意图的问题拆解为 2~4 个具体、明确、互不重复的子问题，
分别覆盖原问题的不同角度。
```

### 4.5 AbstractQueryComponent（公共基类）

**文件**：`service/impl/AbstractQueryComponent.java`

**提供的能力**：
| 方法 | 作用 |
|---|---|
| `loadTemplate(Resource)` | 从 classpath 加载 Prompt 模板文本 |
| `renderTemplate(template, kvs...)` | 简单占位符替换 `{key}` → `value` |
| `callChat(chatModel, promptText, temperature)` | 统一调用 LLM，返回纯文本 |
| `extractJson(text)` | 从 LLM 输出中提取 JSON（兼容 ```json 包裹） |
| `parseJson(jsonText, clazz)` | JSON 反序列化 |
| `parseJsonNode(jsonText)` | 解析为 Jackson `JsonNode` |

---

## 五、与 ChatService 的集成

### 5.1 同步对话流程

在 `ChatServiceImpl.send()` 方法中：

```java
// 步骤 5：Query Router（路由异常不阻断主流程）
try {
    RoutedQuery routed = queryRouterService.route(session.getId(), userId, content);
    log.info("[ChatService] 路由完成: type={}, queriesCount={}, reason={}", ...);
} catch (Exception e) {
    log.warn("[ChatService] QueryRouter 异常，跳过路由阶段: {}", e.getMessage());
}
```

> **注意**：当前 `RoutedQuery` 的结果 **仅用于日志记录**，尚未实际作用于下游 Prompt 构建。下游仍使用原始用户消息 + 完整历史直接调用 LLM 生成回复。这意味着路由系统目前处于"接入但未完全消费"的阶段。

### 5.2 流式对话流程

`sendStream()` 中同样调用了 `queryRouterService.route()`，同样仅记录日志。

---

## 六、单元测试覆盖

**文件**：`test/.../service/impl/QueryRouterServiceTest.java`

使用 Mockito 隔离依赖，覆盖 8 个场景：

| 测试用例 | 验证点 |
|---|---|
| 首轮对话 → SIMPLE | 无历史时直接判定 simple，不调用 LLM |
| SIMPLE → 透传 | 分类为 simple 时不调用 rewriter/expander |
| CONTEXTUAL → 重写 | 调用 rewriter，返回改写后的 query |
| COMPLEX → 扩展 | 调用 expander，返回多个子查询 |
| 分类器失败 → 降级 | 抛异常时降级为 simple |
| 重写器失败 → 回退 | 类型仍为 contextual，queries 回退原 query |
| 扩展器返回空 → 回退 | 空列表时回退原 query |
| 扩展器异常 → 回退 | 抛异常时回退原 query |
| formatHistory 格式 | 仅保留 user/assistant，正确拼接 |

---

## 七、Prompt 模板文件

| 模板文件 | 用途 | 温度 | 输出格式 |
|---|---|---|---|
| `prompts/query/classify.st` | 查询分类 | 0.0 | JSON `{type, reason}` |
| `prompts/query/rewrite.st` | 查询重写 | 0.0 | 纯文本（问题本体） |
| `prompts/query/expand.st` | 查询扩展 | 0.3 | JSON `{queries[], reason}` |

---

## 八、设计亮点

1. **面向接口封装**：对外仅暴露 `QueryRouterService`，三个子组件包私有，符合最小知识原则
2. **统一输出结构**：`RoutedQuery` 作为统一契约，下游只需消费 `queries` 列表
3. **完善的降级策略**：每个环节失败都有回退路径，保障主流程不被阻断
4. **首轮短路优化**：无历史时跳过 LLM 调用，节省成本和延迟
5. **结果合法性校验**：JSON 解析重试 + 长度/空值校验 + 去重截断
6. **LLM 调用抽象**：`AbstractQueryComponent` 统一封装模板渲染、调用、JSON 提取
7. **日志可观测**：每次路由都记录 type、queries 数量、理由、耗时

---

## 九、待改进点 / 观察

### 9.1 路由结果未被实际消费
当前 `RoutedQuery.queries` 仅用于日志，没有参与到后续的 Prompt 构建或检索流程中。建议的消费路径：
- **用于 RAG 检索**：将重写/扩展后的 queries 分别做向量检索，合并召回结果
- **用于 Prompt 增强**：将扩展的子查询作为补充上下文注入 System Prompt
- **用于多轮思考**：对 complex 类型的多子查询分别生成回答，再做汇总

### 9.2 缺少查询重写的历史上下文限制
`QueryRewriter` 接收完整的 5 轮历史文本，没有对历史长度做 token 限制。如果历史较长，可能超出模型上下文或增加成本。

### 9.3 RewriteResult 类未被使用
`RewriteResult` BO 类定义了但 `QueryRewriter.rewrite()` 直接返回 `String`，没有使用这个类。可考虑统一输出结构，或删除冗余类。

### 9.4 分类器使用同一份历史进行分类和重写
分类和重写各自独立渲染 prompt 并调用 LLM，可能存在 2 次 LLM 调用的成本。可考虑在同一个 LLM 调用中完成分类 + 重写（当类型为 contextual 时）。

### 9.5 CONTEXTUAL 类型未考虑历史语义的改写必要性
所有 contextual 类型都触发重写，但有些指代性问题即使不重写，结合完整历史的 LLM 也能理解。重写增加了一次 LLM 调用的延迟和成本。

---

## 十、扩展建议

### 10.1 接入 RAG 链路
将 `RoutedQuery.queries` 作为向量检索的输入，实现：
- SIMPLE → 单 query 检索
- CONTEXTUAL → 重写后 query 检索（提升召回准确率）
- COMPLEX → 多 query 并行检索 + 结果融合

### 10.2 新增路由类型
可扩展 `QueryType` 枚举，新增：
- `KNOWLEDGE` — 明确指向知识库查询
- `CHITCHAT` — 闲聊，跳过检索
- `TOOL_CALL` — 调用工具/函数

### 10.3 流式路由
当前路由是同步阻塞的（最多 3 次 LLM 调用），可考虑：
- 分类结果流式输出，尽早判断类型
- COMPLEX 类型的子查询并行生成

### 10.4 路由结果持久化
将 `RoutedQuery` 写入数据库或日志系统，便于：
- 离线分析路由准确率
- A/B 测试不同 Prompt
- 优化分类/重写/扩展策略
