# RAG 系统分析文档：Groundedness（事实支撑性校验）功能

> 版本：v1.0  
> 日期：2026-08-09  
> 适用版本：ai-assistant 主分支

---

## 1. 概述

### 1.1 什么是 Groundedness

Groundedness（也称为 Faithfulness / Factuality / 事实支撑度）是 RAG 系统中的核心质量指标，用于衡量 AI 生成的回答是否严格基于提供的参考文档，而非模型自行编造（Hallucination）。

本系统实现了 **LLM-as-Judge 模式**的后置 Groundedness 校验：在回答生成完成后，调用独立的 LLM 实例（temperature=0.0）作为"法官"，逐条核对回答中的事实声明与参考文档的对应关系，输出结构化的判决结果，并据此对最终回答进行标记或警示。

### 1.2 功能定位

Groundedness 校验是本系统 **三道防编造防线** 中的最后一道：

| 防线 | 位置 | 机制 | 作用 |
|------|------|------|------|
| 第一道 | 提示词层 | `ANTI_FABRICATION_WITH_CONTEXT` 强制规则（系统提示末尾追加） | 从生成源头约束 LLM 只基于文档回答 |
| 第二道 | 引用标注 | 强制 `[n]` 引用格式 + 上下文编号组装 | 让回答可溯源、便于后续校验 |
| 第三道 | 后置校验 | GroundednessChecker LLM-as-Judge | 对生成结果进行事实审核与标记 |

### 1.3 核心目标

- **识别未支撑内容**：自动检测回答中超出文档范围的事实性声明
- **引用错误检测**：检查 `[n]` 引用编号是否真实支撑对应句子
- **用户透明性**：对低支撑度回答给予明确警示，避免误导
- **容错设计**：校验故障时按 fail-open 策略降级，不阻断核心问答流程

---

## 2. 系统架构

### 2.1 整体 RAG 流程中 Groundedness 的位置

```
用户查询
   │
   ▼
┌──────────────────────┐
│ QueryRouterService   │  分类 / 改写 / 扩展
└──────────┬───────────┘
           │
           ▼
┌──────────────────────┐
│ VectorSearchService  │  向量检索 TopK → TopN
└──────────┬───────────┘
           │
           ▼
┌──────────────────────┐
│   RerankService      │  可选：bge-reranker 重排
└──────────┬───────────┘
           │
           ▼
┌──────────────────────┐
│ ContextAssemblyService  截断 / 格式化 / 计数
└──────────┬───────────┘
           │
           ▼
┌──────────────────────┐
│  RagPromptService    │  系统提示 + 防编造兜底规则
└──────────┬───────────┘
           │
           ▼
┌──────────────────────┐
│  DeepSeek ChatModel  │  生成回答
└──────────┬───────────┘
           │
   ┌───────┴───────┐
   │ 同步 / 流式汇合 │
   └───────┬───────┘
           ▼
┌──────────────────────┐
│ GroundednessChecker  │  ★ 事实校验（后置）
└──────────┬───────────┘
           │
           ▼
┌──────────────────────┐
│ 后处理 & 持久化       │  标记 ⚠️ / 警示头 / 引用错误提示
└──────────┬───────────┘
           ▼
      最终回答
```

### 2.2 模块依赖关系

```
ChatServiceImpl (编排者)
   │
   ├── GroundednessChecker (核心校验器)
   │      ├── extends AbstractQueryComponent (模板加载 / LLM 调用 / JSON 解析)
   │      ├── ChatModel (deepSeekChatModel, 同生成模型)
   │      └── Resource (groundedness-check.st 提示模板)
   │
   └── GroundednessProperties (配置项)
          └── application.yaml [ai.rag.groundedness.*]
```

---

## 3. 核心模块详解

### 3.1 GroundednessChecker（校验器）

**文件**：`src/main/java/org/grayray/aiassistant/rag/groundedness/GroundednessChecker.java`

#### 3.1.1 设计特点

| 特性 | 实现 | 说明 |
|------|------|------|
| 父类 | `AbstractQueryComponent` | 复用项目统一的 LLM 调用 / JSON 解析 / 模板加载模式 |
| 温度 | `CHECK_TEMPERATURE = 0.0` | 保证判决结果的确定性（同一输入多次校验结果一致） |
| 重试 | `MAX_ATTEMPTS = 2` | JSON 解析失败时重试一次；网络异常不重试（重试意义不大） |
| 空上下文 | 返回 `fullySupported` | 无文档时跳过校验，由降级提示负责告知用户 |
| 空回答 | 返回 `fullySupported` | 边界条件保护 |

#### 3.1.2 核心方法 `check(contextText, answer)`

```java
public GroundednessCheckResult check(String contextText, String answer)
```

**执行流程：**

1. **前置判断**：空上下文 / 空回答直接返回 `score=1.0`
2. **模板渲染**：将 `{context}` 和 `{answer}` 填入 `groundedness-check.st`
3. **LLM 调用**：调用 DeepSeek ChatModel，temperature=0.0
4. **JSON 提取与解析**：`extractJson()` → `parseJson()` → `GroundednessCheckResult`
5. **字段补全**：
   - `supported` 为空时，由 `score >= 0.9` 推断
   - `score` 为空时，由 `supported` 映射为 1.0 / 0.0
6. **异常处理**：达到最大重试次数仍失败则抛出 `IllegalStateException`，由调用方按 fail-open 策略处理

### 3.2 GroundednessCheckResult（判决结果模型）

**文件**：`src/main/java/org/grayray/aiassistant/rag/groundedness/GroundednessCheckResult.java`

| 字段 | 类型 | 说明 |
|------|------|------|
| `supported` | `Boolean` | 整体是否被参考文档充分支撑 |
| `score` | `Double` | 支撑度分数 0.0~1.0（被支撑声明数 / 总声明数） |
| `unsupportedSentences` | `List<UnsupportedSentence>` | 未被支撑的句子列表，每项含 `sentence` + `reason` |
| `hasCitationErrors` | `Boolean` | 是否存在引用错误 |
| `citationIssues` | `List<String>` | 引用错误的具体描述 |
| `reason` | `String` | 整体判定理由（日志用） |

### 3.3 GroundednessProperties（配置项）

**文件**：`src/main/java/org/grayray/aiassistant/rag/groundedness/GroundednessProperties.java`  
**配置前缀**：`ai.rag.groundedness`

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `enabled` | `boolean` | `true` | 总开关，false 时完全跳过校验 |
| `threshold` | `double` | `0.7` | 支撑度阈值，低于此值视为"严重不支撑"并追加头部警示 |
| `mark-unsupported` | `boolean` | `true` | 是否在未支撑句子末尾追加 ` ⚠️` 标记 |
| `fail-open` | `boolean` | `true` | 校验异常时是否放行原回答（true=放行，false=返回"校验失败"警示） |
| `timeout-ms` | `long` | `10000` | 校验调用超时（毫秒） |

**当前配置**（`application.yaml` 第 136-141 行）：
```yaml
ai:
  rag:
    groundedness:
      enabled: true
      threshold: 0.7
      mark-unsupported: true
      fail-open: true
      timeout-ms: 10000
```

### 3.4 校验提示模板

**文件**：`src/main/resources/prompts/rag/groundedness-check.st`

#### 3.4.1 模板结构

```
【角色】严格的事实核查员
【参考文档】{context}
【待校验的 AI 回答】{answer}
【核查规则】6 条
【输出要求】严格 JSON，6 个字段
```

#### 3.4.2 核查规则要点

1. **严格支撑**：只认可文档中明确出现或能直接推出的内容（纯常识也算支撑）
2. **零容忍编造**：文档中没有的事实、数据、人名、结论、细节一律视为"未支撑"
3. **引用校验**：`[n]` 引用但编号对应文档片段不支撑该句 → 引用错误
4. **反合取谬误**：不因回答"看起来合理"就判定支撑，必须严格对照文档
5. **客套豁免**：纯客套话（"希望能帮到你"）不属于事实声明，不核查
6. **诚实承认**：回答声明"无法回答" → 判定为完全支撑（鼓励坦诚）

#### 3.4.3 设计考量

- **中文提示 + 中文 LLM**：与 DeepSeek 模型的中文能力匹配，判决更准确
- **temperature=0.0**：保证判决一致性，便于问题复现
- **结构化 JSON 输出**：便于程序化处理，避免正则解析的脆弱性

---

## 4. 集成与编排

### 4.1 ChatServiceImpl 中的集成点

**文件**：`src/main/java/org/grayray/aiassistant/chat/service/impl/ChatServiceImpl.java`

#### 4.1.1 调用触发条件

Groundedness 校验仅在以下条件全部满足时执行（同步/流式共用）：

```
context != null && !context.isEmpty()     // 有 RAG 上下文
&& groundednessProperties.isEnabled()     // 校验已启用
&& degradationReasons.length() == 0       // 未发生导致空上下文的降级
```

**设计理由**：
- 空上下文时，回答本身就不受文档约束，校验无意义
- 降级（如向量库故障）时已有降级提示告知用户，重复校验浪费资源

#### 4.1.2 同步路径（`send()` 方法）

位置：第 210-215 行，在回答生成后、保存消息前执行。

```java
// 8.6 Groundedness Check
if (context != null && !context.isEmpty()
        && groundednessProperties.isEnabled()
        && degradationReasons.length() == 0) {
    aiContent = runGroundednessCheck(context.getText(), aiContent);
}
```

#### 4.1.3 流式路径（`sendStream()` 方法）

位置：第 398-403 行，在流式内容全部接收完毕后、保存前、`done` 事件发送前执行。

```java
// 7.3 Groundedness Check（流式：在完整回答拼装完成后、保存前执行）
String finalContent = fullContent.toString();
if (shouldCheckGroundedness) {
    finalContent = runGroundednessCheck(contextTextForCheck, finalContent);
}
```

**流式路径的特殊考量**：
- 校验是 **阻塞操作**（需等待完整回答 → 调用 LLM 校验 → 返回结果）
- 前端先收到流式 token，最后收到 `done` 事件中的 `finalContent`
- 前端需以 `done` 事件中的 `finalContent` 为准，覆盖之前流式显示的内容
- 这是"流式体验 + 后置校验"的常见折中方案

### 4.2 `runGroundednessCheck()` 后处理逻辑

位置：`ChatServiceImpl.java` 第 584-666 行

完整执行流程：

```
调用 groundednessChecker.check()
   │
   ├─ 异常？
   │    ├─ failOpen=true → 原样返回
   │    └─ failOpen=false → 前置"校验失败"警示
   │
   ├─ 完全支撑（supported && score≥阈值 && 无不支撑句 && 无引用错误）？
   │    └─ 是 → 原样返回
   │
   ├─ 步骤 1：标记未支撑句子（markUnsupported=true 时）
   │    └─ 遍历 unsupportedSentences，在每句末尾追加 " ⚠️"
   │
   ├─ 步骤 2：整体支撑度过低（score < threshold）？
   │    └─ 是 → 头部追加 "⚠️ 经事实校验，本次回答的内容支撑度仅 XX%..."
   │
   └─ 步骤 3：存在引用错误？
        └─ 是 → 末尾追加 "（注：部分引用标注可能存在问题：...）"
```

#### 4.2.1 句子标记算法

```
对每个未支撑句子：
  1. 去掉两端空白
  2. 如果已含 ⚠️，跳过
  3. 尝试匹配带中文/英文标点结尾的版本（。！？；.!?;），替换为 "原句 + ⚠️"
  4. 若未匹配，直接子串替换（可能导致句中标记，但总比漏标好）
```

**已知局限**：基于子串替换，对 LLM 返回的句子原文与回答原文不完全一致时可能漏标。

---

## 5. 数据流向分析

### 5.1 同步模式数据流

```
ChatController.send()
   │
   ▼
ChatServiceImpl.send()
   │
   ├── [检索阶段：路由 → 向量检索 → 重排 → 上下文组装]
   │
   ├── ChatModel.call() → 生成 aiContent
   │
   ├── GroundednessChecker.check(contextText, aiContent)
   │      │
   │      ├── 渲染模板 + 调用 DeepSeek (temp=0)
   │      └── 返回 GroundednessCheckResult
   │
   ├── runGroundednessCheck() → 后处理（标记 / 警示）
   │
   ├── chatMessageMapper.insert() → 持久化（含校验后内容）
   │
   └── 返回 ChatSendResult（含 aiMessage + citations）
```

### 5.2 流式模式数据流

```
ChatController.sendStream()
   │
   ▼
ChatServiceImpl.sendStream() → 返回 SseEmitter
   │
   ├── [同步执行：检索阶段]
   │
   ├── ChatModel.stream() → 逐 token 发送 "message" 事件给前端
   │      │
   │      └── 同时在 fullContent StringBuilder 中累积
   │
   ├── 流式结束（onComplete）
   │      │
   │      ├── GroundednessChecker.check() → 校验完整回答
   │      ├── runGroundednessCheck() → 后处理
   │      ├── chatMessageMapper.insert() → 持久化 finalContent
   │      └── SseEmitter "done" 事件 → 携带 finalContent 供前端覆盖
   │
   └── SseEmitter.complete()
```

---

## 6. 性能分析

### 6.1 延迟影响

| 阶段 | 典型耗时 | 说明 |
|------|----------|------|
| 生成回答 | 1~3s（取决于长度） | 主路径 |
| Groundedness 校验 | 额外 1~3s | 额外一次 LLM 调用，输入 = 上下文 + 回答 |
| **合计** | **2~6s** | 校验使总延迟增加约 30%~100% |

**影响因素**：
- 上下文长度（maxTokens=3000，约 4500 字符）
- 回答长度
- DeepSeek API 响应速度
- 网络延迟

### 6.2 Token 消耗

每次校验额外消耗的 Token 估算：
- **输入**：上下文（~3000 tokens）+ 回答（~500 tokens）+ 提示模板（~300 tokens）≈ **3800 tokens**
- **输出**：结构化 JSON（~200 tokens）
- **每次回答额外成本**：约 4000 tokens

### 6.3 资源占用

- 每个校验请求是一次独立的 DeepSeek API 调用
- 不占用本地显存（模型推理在云端）
- 同步阻塞当前线程（流式路径阻塞完成回调线程）

---

## 7. 准确性分析

### 7.1 LLM-as-Judge 模式的固有局限

| 问题 | 影响 | 本系统缓解措施 |
|------|------|----------------|
| **同犯偏差**（Collusion Bias） | 校验用的 LLM 和生成用的 LLM 是同一模型，可能"互相包庇" | 使用 temperature=0.0 降低随机性；系统提示强调"严格" |
| **长度偏差** | 长回答更容易被判为"未支撑"，因为声明更多 | 使用比例分数（score）而非绝对数量 |
| **位置偏差** | LLM 对上下文开头/结尾的内容更敏感 | 无特殊处理（属于已知风险） |
| **JSON 解析失败** | 偶尔输出非严格 JSON | 重试一次 + extractJson 提取 |

### 7.2 可能的误判场景

| 场景 | 类型 | 原因 |
|------|------|------|
| 同义改写被误判为不支撑 | 假阳性（False Positive） | LLM 法官未能识别语义等价的表述 |
| 隐含推理未被判为支撑 | 假阳性 | 提示要求"直接推出"，但"直接"的边界模糊 |
| 微妙编造未被识别 | 假阴性（False Negative） | 编造内容与文档风格融合，难以察觉 |
| 引用编号错位 | 假阳性引用错误 | LLM 对编号与内容的对应关系判断可能出错 |

### 7.3 可靠性保障

1. **temperature=0.0**：同一输入判决一致，便于定位问题
2. **分级警示而非拒绝回答**：即使校验有误，用户仍能看到回答，只是带有标记
3. **fail-open 策略**：校验故障时放行回答，确保可用性优先
4. **多重防线**：提示词约束 + 引用标注 + 后置校验，层层递进

---

## 8. 错误处理与可靠性

### 8.1 异常场景矩阵

| 异常场景 | 触发条件 | 处理策略 | 用户体验 |
|----------|----------|----------|----------|
| LLM 调用超时 | 网络慢 / 模型忙 | 异常 → fail-open 判定 | 正常收到回答，无校验标记（failOpen=true） |
| LLM 返回非 JSON | 模型输出格式异常 | 重试 1 次 → 仍失败 → 异常 | 同上 |
| 上下文为空 | 无检索结果 / 降级 | 跳过校验，返回 fullySupported | 由降级提示负责告知 |
| 回答为空 | 模型返回空 | 跳过校验 | 不影响 |
| 配置未启用 | `enabled: false` | 直接跳过 | 无校验功能 |

### 8.2 降级路径

```
正常流程：生成 → 校验 → 标记 → 返回
              │
              └─ 校验失败？
                    ├─ failOpen=true  → 返回原始回答（静默降级）
                    └─ failOpen=false → 返回"校验失败"警示 + 原始回答
```

---

## 9. 配置与运维

### 9.1 配置参数详解

| 参数 | 调整建议 | 影响 |
|------|----------|------|
| `enabled` | 生产环境保持 `true` | 关闭则完全失去事实校验 |
| `threshold` | 可在 0.6~0.8 之间调整 | 越低越宽松，越高越严格；过高可能导致大量正常回答被标红 |
| `mark-unsupported` | 建议 `true` | 关闭则只有整体阈值警示，无句子级标记 |
| `fail-open` | 生产环境建议 `true` | 可用性优先；对准确性要求极高的场景可设 `false` |
| `timeout-ms` | 默认 10s 通常足够 | 网络慢时可适当增大 |

### 9.2 监控点

建议在以下位置增加监控告警（目前仅有日志）：

| 监控项 | 日志位置 | 告警建议 |
|--------|----------|----------|
| 校验调用失败率 | `GroundednessChecker.check()` catch 块 | 失败率 > 5% 告警 |
| 平均校验耗时 | （需新增埋点） | P95 > 15s 告警 |
| 低支撑度回答占比 | `ChatServiceImpl.runGroundednessCheck()` | 占比 > 20% 告警（可能检索质量下降） |
| 引用错误率 | 同上 | 持续升高需排查提示模板 |

### 9.3 关键日志

- `[GroundednessChecker] 模板加载完成` — 启动确认
- `[GroundednessChecker] 校验完成: supported=..., score=...` — DEBUG 级别
- `[GroundednessChecker] JSON 解析失败` / `调用失败` — WARN 级别
- `[ChatService] Groundedness 校验通过` — DEBUG 级别
- `[ChatService] Groundedness 支撑度过低` — WARN 级别
- `[ChatService] Groundedness 校验完成: ...` — INFO 级别（结构化汇总）

---

## 10. 与前端的交互

### 10.1 同步接口

- 返回 `ChatSendResult`，其中 `aiMessage.content` 已包含所有校验标记（⚠️、警示头、引用错误注）
- 前端直接渲染即可，无需额外处理

### 10.2 流式接口

- 流式过程中：前端逐字显示（此时内容未经校验）
- `done` 事件：携带 `finalContent`（已完成校验和标记）
- **前端必须**：收到 `done` 事件后，用 `finalContent` 覆盖之前流式显示的内容

**done 事件数据结构**（`ChatStreamEvent.done()`）：
```
{
  event: "done",
  data: {
    sessionId: "...",
    messageId: 123,
    fullContent: "校验后的完整文本（含 ⚠️ 标记等）",
    finishReason: "stop",
    model: "deepseek-v4-flash",
    messageIndex: 3,
    citations: [...]
  }
}
```

---

## 11. 已知不足与改进方向

### 11.1 功能层面

| 不足 | 严重程度 | 改进方向 |
|------|----------|----------|
| 无单元测试覆盖 | 中 | 补充 `GroundednessChecker` 的单元测试（mock LLM 返回） |
| 无评估基准 | 中 | 建立 RAG 评估集（含已知幻觉样本），定期评测校验准确率 |
| 句子标记基于子串匹配 | 低 | 改为更鲁棒的语义匹配或句子边界检测 |
| 不支持流式校验 | 低 | 理论上可做 sentence-level 流式校验，但复杂度高、收益有限 |
| 生成与校验共用同一模型 | 中 | 考虑使用更强的模型做校验（如 DeepSeek 旗舰版），降低同犯偏差 |

### 11.2 架构层面

| 不足 | 严重程度 | 改进方向 |
|------|----------|----------|
| 校验阻塞主流程 | 中 | 可考虑异步校验（先返回回答，后推送校验结果），但需前端配合 |
| 无校验结果持久化 | 低 | 将 `GroundednessCheckResult` 存入数据库，便于质量分析与回溯 |
| 无用户反馈闭环 | 低 | 增加"回答不准确"反馈按钮，与 groundedness 数据关联分析 |
| 每次调用独立 API | 低 | 高并发时考虑批量校验或缓存（但 question-answer 对唯一，缓存价值有限） |

### 11.3 未来扩展方向

1. **多维度质量评估**：除 groundedness 外，增加 relevance（相关性）、completeness（完整性）校验
2. **检索增强迭代**：当 groundedness 分数低时，自动触发二次检索（query 改写 + 重新检索）
3. **自反思生成**：将 groundedness 校验结果反馈给 LLM，要求其重写未支撑部分
4. **引用可视化**：前端点击 `[n]` 可展开对应文档片段，增强可溯源性
5. **A/B 测试框架**：支持不同校验策略的在线对比实验

---

## 12. 相关文件索引

| 文件路径 | 说明 |
|----------|------|
| `rag/groundedness/GroundednessChecker.java` | 核心校验器 |
| `rag/groundedness/GroundednessCheckResult.java` | 判决结果模型 |
| `rag/groundedness/GroundednessProperties.java` | 配置属性类 |
| `resources/prompts/rag/groundedness-check.st` | 校验提示模板 |
| `chat/service/impl/ChatServiceImpl.java` | 编排集成（含 runGroundednessCheck） |
| `rag/rewrite/AbstractQueryComponent.java` | 父类（LLM 调用 / JSON 解析） |
| `rag/prompt/RagPromptService.java` | 防编造兜底规则（第一道防线） |
| `resources/prompts/rag/rag-system.st` | RAG 系统提示模板 |
| `resources/application.yaml` | 配置文件（ai.rag.groundedness 段） |

---

*— 文档结束 —*
