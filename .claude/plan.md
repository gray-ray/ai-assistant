# SimpleVectorStore 向量持久化方案

## 背景

当前 `SimpleVectorStoreService` 基于 Spring AI `SimpleVectorStore`，数据全部存储在 JVM 内存中（`ConcurrentHashMap`），应用重启即丢失。Metadata 中已包含 `documentId`，但向量数据与 `document_info` 表之间缺少持久化层面的关联。

## 目标

1. 将 SimpleVectorStore 生成的向量数据持久化到本地磁盘目录
2. 通过 metadata 中的 `documentId` 与 `document_info` 表建立关联
3. 应用启动时自动从本地目录加载向量数据到内存
4. 确保向量数据和文件信息能够一一对应

## 设计思路

### 存储格式

每个文档对应一个 JSON 文件，文件名为 `doc_{documentId}.json`，存储在配置的目录下。

每个 JSON 文件包含一个文档的所有 chunk 向量数据，结构：
```json
{
  "documentId": 123,
  "embeddingModel": "bge-m3",
  "dimension": 1024,
  "chunks": [
    {
      "chunkId": "doc_123_chunk_0",
      "chunkIndex": 0,
      "totalChunks": 10,
      "documentId": 123,
      "documentName": "xxx.pdf",
      "chapterIndex": 0,
      "chapterTitle": "第一章",
      "tokenCount": 500,
      "content": "xxx",
      "embedding": [0.123, -0.456, ...]
    }
  ]
}
```

### 文件存储目录

默认：`./vector-store/`（可通过配置 `ai.vector-store.persistence-path` 修改）

按文档 ID 分文件存储（而非一个大文件），原因：
- 按文档删除时只需删除单个文件，IO 效率高
- 单个文件损坏不影响其他文档
- 加载时可按需加载（启动全量加载即可，数据量不大）

### 持久化策略

- **saveAll**：写入内存后，按 documentId 分组，追加/覆盖对应 JSON 文件
- **deleteByDocumentId**：从内存删除的同时，删除对应的 JSON 文件
- **启动加载**：`@PostConstruct` 中扫描目录，加载所有 JSON 文件到内存

### documentId 与 document_info 表的关联

Metadata 中已保存 `documentId`，与 `document_info.id` 是一一对应的业务主键关系。无需额外建表，关联关系已经存在于向量的 metadata 中。

为了强化数据一致性，在加载向量文件时做校验：
- 如果某个向量文件对应的 `documentId` 在 `document_info` 表中不存在（或已逻辑删除），则跳过加载并记录日志
- 删除文档时（如未来有删除接口），同时清理向量文件

## 改动文件清单

### 1. `application.yaml` — 新增配置项
```yaml
ai:
  vector-store:
    log-sample-vector: false
    # 向量持久化目录（相对路径或绝对路径），留空则不持久化（纯内存模式）
    persistence-path: ./vector-store
```

### 2. 新建 `VectorStorePersistenceProperties.java`
位置：`config/` 包
- 读取 `ai.vector-store.persistence-path` 配置
- 提供目录存在性校验、自动创建目录能力

### 3. 新建 `VectorChunkRecord.java` 和 `DocumentVectorRecord.java`
位置：`dto/` 包
- `VectorChunkRecord`：单个 chunk 的持久化结构（id, content, metadata, embedding）
- `DocumentVectorRecord`：文档级持久化结构（documentId, model, dimension, chunks 列表）
- 使用 Jackson 序列化/反序列化

### 4. 新建 `VectorStorePersistenceManager.java`
位置：`service/impl/` 或 `common/` 包
- `saveDocumentVectors(long documentId, List<SimpleVectorStoreContent> contents)` — 将一个文档的所有向量写入 JSON 文件
- `loadAllVectors()` — 扫描目录，加载所有文档的向量到内存，返回 `Map<String, SimpleVectorStoreContent>`
- `deleteDocumentVectors(long documentId)` — 删除指定文档的向量文件
- 内部使用 Jackson `ObjectMapper`，加文件锁或同步控制避免并发写入问题

### 5. 修改 `SimpleVectorStoreService.java`
- 注入 `VectorStorePersistenceManager` 和 `DocumentInfoMapper`
- `@PostConstruct` 中：如果配置了持久化目录，则从磁盘加载所有向量到内存 + 重建 `docToChunkIds` 映射
- `saveAll()` 中：写完内存后，按 documentId 分组调用持久化管理器写入磁盘
- `deleteByDocumentId()` 中：删除内存后，同步删除磁盘文件
- 加载时校验 documentId 在 document_info 表中是否存在且未删除，不存在则跳过并清理脏文件

### 6. `schema.sql` — 无需修改
- `document_info` 表已存在，`documentId` 作为业务主键已在 metadata 中保存

## 数据流

### 写入流程
```
EmbeddingServiceImpl.embedAndStore()
  → VectorStoreService.saveAll(embeddedChunks)
  → SimpleVectorStoreService.saveAll()
    → 写入内存 store (ConcurrentHashMap)
    → 按 documentId 分组
    → 对每组调用 persistenceManager.saveDocumentVectors(docId, contents)
      → 序列化为 JSON
      → 写入 vector-store/doc_{documentId}.json
```

### 启动加载流程
```
@PostConstruct
  → persistenceManager.loadAllVectors()
    → 扫描 vector-store/ 目录
    → 对每个 doc_{id}.json 文件：
      → 读取并反序列化
      → 校验 documentId 在 document_info 表中存在且 is_deleted=0
      → 有效则加载到内存 store，跳过无效数据（并删除脏文件）
    → 返回全部 content，填充 store 和 docToChunkIds
```

### 删除流程
```
deleteByDocumentId(documentId)
  → 从内存 store 删除
  → 从 docToChunkIds 删除
  → persistenceManager.deleteDocumentVectors(documentId)
    → 删除 vector-store/doc_{documentId}.json
```

## 边界情况处理

1. **JSON 文件损坏**：捕获异常，记录错误日志，跳过该文件，不影响其他文档加载
2. **documentId 对应文档已删除**：加载时校验 document_info，无效则删除脏向量文件
3. **并发写入同一文档**：`saveAll` 方法对同一 documentId 的写入加 synchronized 或使用文件锁
4. **向量维度/模型变化**：在文件中保存 embeddingModel 和 dimension，加载时校验一致性，不匹配则跳过并告警
5. **持久化目录配置为空**：保持纯内存模式，兼容现有行为
