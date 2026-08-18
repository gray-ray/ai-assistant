# 文档 Chunk 功能 — 系统分析文档

> 版本：v1.0  
> 日期：2026-08-18  
> 项目：ai-assistant（Spring Boot 3.4 + Spring AI 1.1）  
> 范围：文档解析切分 → Chunk 业务 ID → Chunk 入库 → 向量 metadata → 检索过滤 → 引用溯源

---

## 1. 系统概述

文档 Chunk 功能是 RAG 系统中的基础能力层，负责将上传文档解析后的长文本切分为可向量化、可检索、可追踪的小片段，并把这些片段同步持久化到 MySQL 与向量库中。

当前实现已经从“只把切片写入向量库”扩展为“双写结构”：

1. `document_chunk` 表保存 Chunk 文本、业务 ID、知识库 ID、文档 ID、章节、token、hash、向量 ID 和扩展元数据。
2. SimpleVectorStore 保存 embedding 向量，并在 metadata 中写入 `chunkId`、`knowledgeId`、`documentId` 等过滤与溯源字段。
3. RAG 检索通过 `VectorSearchRequest.knowledgeId` 和 `MetadataFilter` 限定召回范围。

### 1.1 功能定位

Chunk 功能位于“文档处理”和“向量检索”之间，是文档内容进入 RAG 系统的标准化中间层。

```text
上传文档
  ↓
document_info
  ↓
PDF 文本提取
  ↓
文本清洗
  ↓
TextChunkService 切分
  ↓
document_chunk 入库
  ↓
EmbeddingService 向量化
  ↓
SimpleVectorStore 写向量 + metadata
  ↓
VectorSearchService 检索
  ↓
RAG 上下文 / 引用来源
```

### 1.2 解决的问题

| 问题 | Chunk 功能的解决方式 |
|------|----------------------|
| 原始文档过长，无法直接进入模型上下文 | 按章节、段落、token 长度分层切分 |
| 向量库只有向量，缺少业务可追踪数据 | `document_chunk` 表保存文本和结构化元数据 |
| 无法稳定定位某个召回片段 | 使用全局唯一 `chunk_id` 作为业务主键 |
| 知识库之间可能互相召回 | Chunk、向量 metadata、检索请求都携带 `knowledgeId` |
| 文档处理失败后难以排查 | 保存 `content_hash`、`metadata_json`、`process_status/process_error` |
| 后续切换 Milvus/PGVector 缺少业务字段 | `chunkId/documentId/knowledgeId` 已在 DB 与 metadata 中对齐 |

---

## 2. 技术栈与模块位置

### 2.1 技术栈

| 层次 | 技术 | 说明 |
|------|------|------|
| 框架 | Spring Boot 3.4.4 | 应用基础框架 |
| ORM | MyBatis-Plus 3.5.7 | `BaseMapper`、`IService`、`ServiceImpl`、逻辑删除 |
| 文档解析 | Apache PDFBox 3.0.5 | 当前 PDF 文本提取 |
| Token 估算 | jtokkit | `CL100K_BASE` 编码 |
| 向量化 | Spring AI `EmbeddingModel` | 批量生成 embedding |
| 向量存储 | Spring AI `SimpleVectorStore` | 当前默认内存向量库，支持本地持久化 |
| 数据库 | MySQL 8.x | 保存文档、知识库、Chunk 元数据 |

### 2.2 模块分布

```text
ai-assistant-document
├── entity
│   ├── DocumentInfo
│   └── DocumentChunk
├── mapper
│   └── DocumentChunkMapper
├── service
│   ├── TextChunkService
│   ├── DocumentChunkService
│   └── DocumentProcessService
└── service.impl
    ├── TextChunkServiceImpl
    ├── DocumentChunkServiceImpl
    └── DocumentProcessServiceImpl

ai-assistant-rag-api
├── model
│   ├── TextChunk
│   └── EmbeddedChunk
└── retrieval
    ├── VectorSearchRequest
    └── RetrievedChunk

ai-assistant-rag-core
├── service.impl
│   ├── EmbeddingServiceImpl
│   └── SimpleVectorStoreService
└── retrieval
    ├── VectorSearchServiceImpl
    └── filter/MetadataFilter
```

---

## 3. 核心数据模型

### 3.1 TextChunk

`TextChunk` 是文档切分后的内存模型，位于 `ai-assistant-rag-api`，用于在文档模块、embedding 模块、向量存储模块之间传递 Chunk 数据。

| 字段 | 说明 |
|------|------|
| `chunkId` | Chunk 业务 ID，当前格式 `doc_{documentId}_v{chunkVersion}_chunk_{chunkIndex}` |
| `knowledgeId` | 所属知识库 ID，可为空以兼容历史/临时文档 |
| `chunkVersion` | 切分版本，默认 1，重试或重新切分时递增 |
| `content` | Chunk 文本内容 |
| `chunkIndex` | 当前版本内的全局顺序，从 0 开始 |
| `totalChunks` | 当前文档当前版本的 Chunk 总数 |
| `chapterTitle` | 章节标题 |
| `chapterIndex` | 章节序号 |
| `documentId` | 所属文档 ID |
| `pageNumber` | PDF 页码预留字段 |
| `documentName` | 原始文件名 |
| `tokenCount` | 当前 Chunk token 估算数 |

### 3.2 DocumentChunk

`DocumentChunk` 是 MySQL 持久化实体，对应 `document_chunk` 表。

| 字段 | 说明 |
|------|------|
| `id` | 数据库自增主键 |
| `chunkId` | 稳定业务 ID，与向量 ID 对齐 |
| `documentId` | 所属文档 ID |
| `knowledgeId` | 所属知识库 ID |
| `chunkVersion` | 切分版本 |
| `chunkIndex` | Chunk 顺序 |
| `totalChunks` | 文档 Chunk 总数 |
| `content` | Chunk 文本 |
| `contentHash` | `content` 的 SHA-256 |
| `pageNumber` | 页码 |
| `chapterIndex` | 章节序号 |
| `chapterTitle` | 章节标题 |
| `tokenCount` | token 估算数 |
| `vectorId` | 向量库 ID，当前回填为 `chunkId` |
| `metadata` | JSON 扩展元数据，对应 `metadata_json` |
| `createTime/updateTime` | 创建/更新时间 |
| `isDeleted` | 逻辑删除标识 |

### 3.3 数据表约束

`document_chunk` 的关键约束如下：

| 约束 / 索引 | 用途 |
|-------------|------|
| `uk_chunk_id(chunk_id)` | 保证 Chunk 业务 ID 全局唯一 |
| `uk_doc_version_chunk(document_id, chunk_version, chunk_index)` | 保证同一文档同一版本下 Chunk 顺序唯一 |
| `idx_document_id(document_id)` | 按文档查询 Chunk |
| `idx_knowledge_id(knowledge_id)` | 按知识库查询 Chunk |
| `idx_knowledge_doc(knowledge_id, document_id)` | 查询某知识库某文档下 Chunk |
| `idx_document_chunk(document_id, chunk_version, chunk_index)` | 按文档版本顺序回放 Chunk |
| `idx_vector_id(vector_id)` | 通过向量 ID 反查 Chunk |

---

## 4. Chunk 生成流程

### 4.1 文档处理入口

文档上传后，`DocumentUploadServiceImpl` 写入 `document_info`，并异步调用：

```java
documentProcessService.processDocument(documentId);
```

`DocumentProcessServiceImpl` 是 Chunk 功能的编排入口，核心流程如下：

```text
1. 查询 document_info
2. 校验文件类型与本地文件
3. document_info.process_status = processing
4. PDFBox 提取 rawText
5. TextCleanService 清洗 cleanedText
6. DocumentChunkService 查询 nextChunkVersion
7. TextChunkService 生成 List<TextChunk>
8. DocumentChunkService.saveTextChunks 批量入库
9. EmbeddingService.embedAndStore 向量化并写向量库
10. DocumentChunkService.markVectorIds 回填 vector_id
11. document_info.process_status = completed
```

### 4.2 切分版本

当前实现支持从数据库查询下一切分版本：

```java
Integer chunkVersion = knowledgeId == null
        ? 1
        : documentChunkService.nextChunkVersion(documentInfo.getId());
```

规则：

| 场景 | 版本 |
|------|------|
| 文档从未切分 | `1` |
| 文档已有版本 1 | 下一次为 `2` |
| 文档已有版本 N | 下一次为 `N + 1` |
| 非知识库文档 | 保持 `1` |

这样可以避免同一文档失败重试或重新切分时撞上 `uk_doc_version_chunk` 唯一约束。

### 4.3 Chunk ID 规则

当前 Chunk ID 格式：

```text
doc_{documentId}_v{chunkVersion}_chunk_{chunkIndex}
```

示例：

```text
doc_12_v1_chunk_0
doc_12_v1_chunk_1
doc_12_v2_chunk_0
```

该 ID 会同步用于：

1. `TextChunk.chunkId`
2. `DocumentChunk.chunkId`
3. `DocumentChunk.vectorId`
4. `SimpleVectorStoreContent.id`
5. `VectorStore metadata.chunkId`
6. `RetrievedChunk.chunkId`

---

## 5. 切分算法分析

### 5.1 分层切分策略

`TextChunkServiceImpl` 采用分层切分：

```text
清洗后全文
  ↓
按章节切分
  ↓
按段落切分
  ↓
按 token 长度切分
  ↓
添加 overlap
  ↓
注入 metadata
```

### 5.2 章节识别

当前识别以下标题形式：

| 类型 | 示例 |
|------|------|
| 中文章节 | `第一章`、`第3节`、`第十二篇` |
| Markdown 标题 | `# 标题`、`## 标题` |
| 数字编号标题 | `1. 概述`、`1.2 背景`、`1、范围` |
| 中文编号标题 | `一、项目背景`、`二. 技术要求` |

标题行长度超过 80 字符时不会被识别为标题，避免普通长句被误判。

### 5.3 段落切分

段落按空行切分：

```text
\n\s*\n
```

过短段落会并入上一段，当前最小长度：

```java
MIN_CHUNK_CHARS = 20
```

### 5.4 Token 切分

当前目标大小：

```java
CHUNK_SIZE = 500
CHUNK_OVERLAP = 50
```

当段落 token 数超过 500 时，优先在句末标点处断开：

```text
。！？.!?；;
```

找不到合适断点时才按 token 硬切。

### 5.5 Overlap

同一章节内，相邻 Chunk 会在后一个 Chunk 开头拼接前一个 Chunk 末尾的 50 个 token。这样可以减少语义断裂，提升跨段落问题的召回稳定性。

---

## 6. Chunk 入库流程

### 6.1 saveTextChunks

`DocumentChunkServiceImpl.saveTextChunks` 负责把 `List<TextChunk>` 转成 `List<DocumentChunk>` 并批量保存。

该方法会补齐关键字段：

1. 如果 `chunkVersion` 为空，使用默认版本 1。
2. 如果 `chunkId` 为空，按 `doc_{documentId}_v{version}_chunk_{chunkIndex}` 生成。
3. 强制同步 `documentId`、`knowledgeId`、`chunkVersion` 回 `TextChunk`，保证后续 embedding metadata 使用相同数据。

### 6.2 metadata_json

`metadata_json` 当前包含：

| Key | 来源 |
|-----|------|
| `chunkId` | `TextChunk.chunkId` |
| `knowledgeId` | `TextChunk.knowledgeId` |
| `documentId` | `TextChunk.documentId` |
| `documentName` | `TextChunk.documentName` |
| `chunkIndex` | `TextChunk.chunkIndex` |
| `totalChunks` | `TextChunk.totalChunks` |
| `chunkVersion` | `TextChunk.chunkVersion` |
| `chapterIndex` | `TextChunk.chapterIndex` |
| `chapterTitle` | `TextChunk.chapterTitle` |
| `pageNumber` | `TextChunk.pageNumber` |
| `tokenCount` | `TextChunk.tokenCount` |

### 6.3 content_hash

`content_hash` 使用 SHA-256：

```text
SHA-256(UTF-8(content))
```

用途：

1. 判断重试前后文本是否一致。
2. 后续做 Chunk 去重。
3. 排查向量库与数据库不一致时校验内容。

### 6.4 vector_id 回填

向量写入成功后：

```java
documentChunkService.markVectorIds(documentId, chunkVersion, chunks);
```

当前回填规则：

```text
document_chunk.vector_id = chunk.chunkId
```

这是因为 SimpleVectorStore 使用 `chunkId` 作为向量 ID。未来接入 Milvus/PGVector 时，如果向量库返回独立主键，也可以改为回填外部向量 ID，同时继续保留 `chunkId` 作为业务主键。

---

## 7. 向量化与向量库 Metadata

### 7.1 EmbeddingService

`EmbeddingServiceImpl.embedAndStore` 执行两件事：

```text
TextChunk
  ↓
EmbeddingModel.embed(List<String>)
  ↓
EmbeddedChunk
  ↓
VectorStoreService.saveAll
```

批量大小由配置控制：

```yaml
ai:
  embedding:
    batch-size: 16
```

### 7.2 SimpleVectorStoreService

`SimpleVectorStoreService.saveAll` 使用预计算 embedding，直接写入 SimpleVectorStore 的内存 `store`，避免 Spring AI 再次调用 embedding。

向量 ID 规则：

```java
String chunkId = chunk.getChunkId() != null && !chunk.getChunkId().isBlank()
        ? chunk.getChunkId()
        : String.format("doc_%d_v%d_chunk_%d", ...);
```

### 7.3 VectorStore metadata

向量 metadata 保存以下字段：

| 字段 | 作用 |
|------|------|
| `chunkId` | 向量业务 ID，引用和回查使用 |
| `knowledgeId` | 知识库隔离过滤 |
| `documentId` | 文档过滤和删除 |
| `chunkVersion` | 区分同一文档不同切分版本 |
| `chunkIndex` | 片段顺序 |
| `totalChunks` | 文档片段总数 |
| `pageNumber` | 页码引用预留 |
| `chapterIndex` | 章节序号 |
| `chapterTitle` | 引用展示 |
| `documentName` | 引用展示 |
| `tokenCount` | 上下文预算和排查 |

### 7.4 持久化策略

当前 SimpleVectorStore 仍通过 `VectorStorePersistenceManager` 按文档粒度保存：

```text
vector-store/doc_{documentId}.json
```

知识库表中已经有 `vector_store_path`，但当前向量持久化尚未按知识库分目录落盘。现阶段依靠 metadata 中的 `knowledgeId` 做逻辑隔离。

---

## 8. 检索过滤链路

### 8.1 检索请求

`VectorSearchRequest` 增加：

```java
private Long knowledgeId;
private List<Long> documentIds;
```

在聊天链路中，`ChatServiceImpl` 从会话读取知识库 ID：

```java
VectorSearchRequest.builder()
        .queries(searchQueries)
        .knowledgeId(session.getKnowledgeId())
        .build()
```

这使前端不需要在发送消息时重复传入知识库 ID，也避免检索入口直接相信客户端传参。

### 8.2 检索结果

`RetrievedChunk` 增加：

```java
private String chunkId;
private Long knowledgeId;
private Long documentId;
```

`SimpleVectorStoreService.toRetrievedChunk` 会从 metadata 中还原这些字段。

### 8.3 MetadataFilter

`MetadataFilter.apply` 支持两类过滤：

```text
knowledgeId 过滤：只保留同知识库 Chunk
documentIds 过滤：只保留指定文档 Chunk
```

两者同时存在时取交集。

对于 SimpleVectorStore，过滤发生在相似度召回之后；对于 Milvus/PGVector，后续可以把过滤条件下推到向量查询表达式。

---

## 9. 状态机与异常处理

### 9.1 文档处理状态

```text
pending
  ↓
processing
  ├── 成功 → completed
  └── 失败 → failed
```

### 9.2 异常场景

| 场景 | 当前处理 |
|------|----------|
| `document_info` 不存在 | 记录 warn 并返回 |
| 非 PDF 文件 | 标记 `completed`，不生成 Chunk |
| 本地文件不存在 | 标记 `failed` |
| PDF 解析失败 | 标记 `failed`，写入 `process_error` |
| 文本清洗失败 | 标记 `failed` |
| Chunk 入库失败 | 标记 `failed` |
| Embedding 失败 | 标记 `failed`，已入库 Chunk 保留 |
| vector_id 回填失败 | 外层捕获后标记 `failed` |

### 9.3 一致性状态

理想完成状态：

```text
document_info.process_status = completed
document_chunk 已存在
document_chunk.vector_id = document_chunk.chunk_id
SimpleVectorStoreContent.id = document_chunk.chunk_id
SimpleVectorStore metadata.chunkId = document_chunk.chunk_id
SimpleVectorStore metadata.knowledgeId = document_chunk.knowledge_id
```

可能的不一致状态：

| 状态 | 原因 | 后续补偿建议 |
|------|------|--------------|
| DB 有 Chunk，无向量 | embedding 或向量写入失败 | 提供按 documentId 重试 |
| 向量已写入，vector_id 未回填 | 回填阶段失败 | 按 `chunk_id` 修复 `vector_id` |
| 旧版本 Chunk 未删除 | 重新切分后未清理旧版本 | 成功后逻辑删除旧版本并删旧向量 |
| 向量 metadata 缺 knowledgeId | 历史数据 | 重建向量或迁移 metadata |

---

## 10. 权限与隔离分析

### 10.1 知识库隔离字段

`knowledgeId` 贯穿以下层次：

| 层次 | 字段 |
|------|------|
| 文档表 | `document_info.knowledge_id` |
| Chunk 表 | `document_chunk.knowledge_id` |
| 切分模型 | `TextChunk.knowledgeId` |
| 向量 metadata | `knowledgeId` |
| 检索请求 | `VectorSearchRequest.knowledgeId` |
| 检索结果 | `RetrievedChunk.knowledgeId` |
| 会话表 | `chat_session.knowledge_id` |

### 10.2 当前隔离方式

当前 SimpleVectorStore 是全局内存向量库，实际隔离由 metadata 后过滤实现：

```text
全局相似度召回
  ↓
MetadataFilter 按 knowledgeId 过滤
  ↓
返回当前知识库结果
```

这种方式实现成本低，适合当前开发阶段。数据量增大后，建议切换为按知识库分 collection 或把过滤下推到向量数据库。

---

## 11. 性能分析

### 11.1 写入性能

Chunk 处理主要耗时点：

| 阶段 | 性能特征 |
|------|----------|
| PDFBox 文本提取 | 与 PDF 页数和复杂度相关 |
| 文本清洗 | 线性扫描，通常较快 |
| jtokkit token 切分 | 与文本长度相关 |
| Embedding | 最主要耗时点，受模型和硬件影响 |
| document_chunk 入库 | 当前 `saveBatch` 批量写入 |
| 向量持久化 | 当前按文档 JSON 文件保存 |

### 11.2 查询性能

当前 SimpleVectorStore 检索是内存全量相似度计算，再做 metadata 后过滤。

优点：

1. 实现简单。
2. 开发环境零依赖。
3. 数据量小时响应快。

限制：

1. 向量数量大时全量扫描成本高。
2. 后过滤可能导致 TopK 被过滤后结果不足。
3. 多知识库共享同一内存 store，内存占用随总 Chunk 数增长。

### 11.3 索引收益

MySQL 不做向量计算，但负责 Chunk 查询、调试、统计和重建。关键查询可被索引覆盖：

```sql
-- 查询文档下所有 Chunk
SELECT * FROM document_chunk
WHERE document_id = ?
ORDER BY chunk_version, chunk_index;

-- 查询知识库下某文档 Chunk
SELECT * FROM document_chunk
WHERE knowledge_id = ? AND document_id = ?
ORDER BY chunk_version, chunk_index;

-- 通过业务 ID 定位 Chunk
SELECT * FROM document_chunk
WHERE chunk_id = ?;
```

---

## 12. 测试与验证

### 12.1 已验证内容

当前相关编译和单测验证：

```powershell
.\mvnw.cmd -DskipTests compile
```

结果：全模块编译通过。

```powershell
.\mvnw.cmd -pl ai-assistant-rag-core -am -Dtest=VectorSearchServiceImplTest "-Dsurefire.failIfNoSpecifiedTests=false" test
```

结果：`VectorSearchServiceImplTest` 通过。

### 12.2 新增测试覆盖

`VectorSearchServiceImplTest` 已增加 `knowledgeId` 过滤测试：

```text
给定同一次召回中包含 knowledgeId=10 和 knowledgeId=20 的 Chunk
当 VectorSearchRequest.knowledgeId = 10
则最终结果只保留 knowledgeId=10 的 Chunk
```

### 12.3 建议补充测试

| 测试 | 目标 |
|------|------|
| `TextChunkServiceImplTest` | 验证 chunkId、chunkVersion、totalChunks 生成 |
| `DocumentChunkServiceImplTest` | 验证入库字段映射、contentHash、nextChunkVersion |
| `DocumentProcessServiceImplTest` | 验证处理编排顺序和失败状态 |
| 集成测试：上传 PDF 到知识库 | 验证 `document_info`、`document_chunk`、向量 metadata 全链路 |
| 集成测试：知识库会话检索 | 验证跨知识库不会召回 |

---

## 13. 当前约束与后续优化

### 13.1 当前约束

1. 当前主要支持 PDF，非 PDF 文件不会解析生成 Chunk。
2. SimpleVectorStore 仍是全局向量库，依靠 metadata 后过滤做知识库隔离。
3. `vector_store_path = vector-store/kb_{knowledgeId}` 已写入知识库表，但尚未真正作为 SimpleVectorStore 分目录加载策略。
4. Chunk 查询接口当前按文档返回全部 Chunk，大文档场景需要分页。
5. 文档重新切分时已支持新版本号，但成功后逻辑删除旧版本 Chunk 和删除旧向量的流程尚未完善。
6. 缺少后台补偿任务处理“DB 有 Chunk 无向量”“向量有 metadata 但 DB 无 Chunk”的不一致状态。

### 13.2 优化建议

| 优先级 | 优化项 | 建议方案 |
|--------|--------|----------|
| 高 | 文档处理重试 | 针对 `process_status=failed` 提供重试接口，使用新 `chunkVersion` |
| 高 | 旧版本清理 | 新版本处理成功后逻辑删除旧 Chunk 并删除旧向量 |
| 高 | Chunk 查询分页 | `/knowledge/document/{documentId}/chunks` 增加 page/pageSize |
| 中 | 按知识库分目录持久化 | SimpleVectorStore 按 `kb_{knowledgeId}/doc_{documentId}.json` 保存 |
| 中 | 向量一致性修复任务 | 定时扫描 DB 与向量库差异并修复 |
| 中 | 多格式解析 | 接入 Tika / POI 支持 Word、TXT、PPT 等 |
| 中 | 可配置 chunk 参数 | 将 `CHUNK_SIZE`、`CHUNK_OVERLAP` 移到配置文件 |
| 低 | Chunk 统计接口 | 返回知识库 Chunk 数、token 总量、处理状态分布 |
| 低 | 切换专业向量库 | Milvus/PGVector 中下推 `knowledgeId/documentId` 过滤 |

---

## 14. 关键文件清单

| 文件 | 职责 |
|------|------|
| `ai-assistant-rag-api/src/main/java/org/grayray/aiassistant/rag/model/TextChunk.java` | Chunk 内存模型 |
| `ai-assistant-document/src/main/java/org/grayray/aiassistant/document/entity/DocumentChunk.java` | Chunk 持久化实体 |
| `ai-assistant-document/src/main/java/org/grayray/aiassistant/document/mapper/DocumentChunkMapper.java` | Chunk Mapper |
| `ai-assistant-document/src/main/java/org/grayray/aiassistant/document/service/TextChunkService.java` | 文本切分接口 |
| `ai-assistant-document/src/main/java/org/grayray/aiassistant/document/service/impl/TextChunkServiceImpl.java` | 文本切分实现 |
| `ai-assistant-document/src/main/java/org/grayray/aiassistant/document/service/DocumentChunkService.java` | Chunk 持久化服务接口 |
| `ai-assistant-document/src/main/java/org/grayray/aiassistant/document/service/impl/DocumentChunkServiceImpl.java` | Chunk 入库、版本、vectorId 回填 |
| `ai-assistant-document/src/main/java/org/grayray/aiassistant/document/service/impl/DocumentProcessServiceImpl.java` | 文档处理编排 |
| `ai-assistant-rag-core/src/main/java/org/grayray/aiassistant/rag/service/impl/EmbeddingServiceImpl.java` | 向量化 |
| `ai-assistant-rag-core/src/main/java/org/grayray/aiassistant/rag/service/impl/SimpleVectorStoreService.java` | 向量存储和 metadata 写入 |
| `ai-assistant-rag-api/src/main/java/org/grayray/aiassistant/rag/retrieval/VectorSearchRequest.java` | 检索请求过滤字段 |
| `ai-assistant-rag-api/src/main/java/org/grayray/aiassistant/rag/retrieval/RetrievedChunk.java` | 检索结果 Chunk |
| `ai-assistant-rag-core/src/main/java/org/grayray/aiassistant/rag/retrieval/filter/MetadataFilter.java` | knowledgeId/documentIds 后过滤 |
| `ai-assistant-server/src/main/resources/db/v3_knowledge_base_migration.sql` | Chunk 表迁移 SQL |
| `ai-assistant-server/src/main/java/org/grayray/aiassistant/config/KnowledgeSchemaMigrator.java` | 启动时幂等补齐表结构 |

---

## 15. 总结

文档 Chunk 功能当前已经形成基础闭环：

1. 文档处理阶段可以生成稳定 `chunkId` 和递增 `chunkVersion`。
2. Chunk 文本和元数据会持久化到 `document_chunk` 表。
3. 向量库使用同一个 `chunkId` 作为向量业务 ID。
4. `knowledgeId` 已进入 Chunk、DB、向量 metadata、检索请求和检索结果。
5. RAG 检索可以按知识库进行 metadata 过滤，避免跨知识库召回。
6. 编译和核心检索过滤单测已通过。

该功能为后续文档重试、向量重建、引用增强、按知识库分目录持久化以及专业向量数据库切换提供了稳定的数据基础。
