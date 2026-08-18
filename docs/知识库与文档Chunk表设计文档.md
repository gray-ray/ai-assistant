# 知识库与文档 Chunk 表设计文档

> 版本：v1.1
> 日期：2026-08-17
> 项目：ai-assistant（Spring Boot 3.4 + Spring AI 1.1）

---

## 1. 概述

### 1.1 目标

基于已有的 `sys_user`、`chat_session`、`chat_message`、`document_info` 四张表，新增 **知识库（knowledge_base）** 和 **文档 Chunk（document_chunk）** 两张表，并对 `document_info`、`chat_session` 和 RAG 检索模型做配套扩展，形成完整的 RAG（Retrieval-Augmented Generation）知识库存储结构，支撑：

- 用户创建/管理多个知识库（如按项目、按招标任务隔离）
- 文档（PDF/Word/TXT 等）上传后解析、切分 Chunk、Embedding 向量化
- Chunk 文本、稳定业务 ID、向量 ID、章节信息和元数据持久化
- 会话绑定知识库，实现“针对某个知识库问答”的 RAG 聊天
- 从 `SimpleVectorStore` 切换到 Milvus / PGVector 时，数据库结构和业务元数据保持稳定

### 1.2 设计原则

1. **与现有项目风格保持一致**：沿用 `id BIGINT AUTO_INCREMENT`、`is_deleted` 逻辑删除、`create_time` / `update_time` 自动填充、`idx_` 前缀索引、中文 COMMENT 等约定。
2. **知识库隔离必须闭环**：`knowledge_id` 不只存在于数据库，还必须进入 Chunk metadata、向量库 metadata、检索请求和过滤逻辑，避免跨知识库召回。
3. **Chunk 要有稳定业务 ID**：除数据库自增 `id` 外，增加 `chunk_id`，与向量库中的业务主键、引用来源、重建任务对齐。
4. **向量库可插拔**：`knowledge_base.vector_store_type` 标识向量库类型（SIMPLE / MILVUS / PGVECTOR），业务侧通过统一 `VectorStoreFactory` 加载。
5. **Chunk 粒度存储**：单个 PDF 可能很大（GB 级），但单个 Chunk 通常几百到几千 Token，因此 `document_chunk.content` 使用 `TEXT`（最大 64KB），不使用 `LONGTEXT`。
6. **元数据可扩展**：常用字段结构化存储，变化快或不同解析器特有的字段放入 `metadata_json`。

---

## 2. 技术栈

| 层次 | 技术 | 版本 / 说明 |
|------|------|-------------|
| 语言 | Java | 21 |
| 框架 | Spring Boot | 3.4.4 |
| AI 抽象 | Spring AI | 1.1.2（`DocumentReader` / `DocumentSplitter` / `VectorStore`） |
| 向量库（默认） | SimpleVectorStore | Spring AI 内置，JSON 文件持久化 |
| 向量库（未来） | Milvus / PGVector | 通过 `vector_store_type` 扩展 |
| Embedding 模型 | 已在 `application.yaml` 配置 | 用于文本向量化 |
| ORM | MyBatis-Plus | 3.5.7（`IService` / `ServiceImpl` 模式） |
| 数据库 | MySQL | 8.x，`ai_assistant` 库 |

---

## 3. 数据库设计

### 3.1 ER 关系总览

```text
sys_user
   │
   ├── 1:N ──→ knowledge_base ── 1:N ──→ document_info ── 1:N ──→ document_chunk
   │                                                       │              │
   │                                                       │              ↓
   │                                                       └────→ VectorStore metadata
   │
   └── 1:N ──→ chat_session ── knowledge_id ──→ knowledge_base
                     │
                     └── 1:N ──→ chat_message ── citations_json
```

完整链路：

```text
sys_user
  ↓
knowledge_base
  ↓
document_info
  ↓
document_chunk（chunk_id / knowledge_id / document_id / chapter / metadata）
  ↓
EmbeddingModel
  ↓
VectorStore（metadata 必须包含 chunkId / knowledgeId / documentId）
  ↓
向量检索（按 knowledgeId 过滤） → RAG 回答 → chat_message.citations_json
```

### 3.2 `knowledge_base` 知识库表

```sql
CREATE TABLE IF NOT EXISTS knowledge_base (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '知识库ID',
    user_id BIGINT NOT NULL COMMENT '创建用户ID',
    name VARCHAR(100) NOT NULL COMMENT '知识库名称',
    description VARCHAR(500) DEFAULT NULL COMMENT '知识库描述',
    vector_store_type VARCHAR(50) NOT NULL DEFAULT 'SIMPLE'
        COMMENT '向量库类型 SIMPLE/MILVUS/PGVECTOR 等',
    vector_store_path VARCHAR(1000) DEFAULT NULL
        COMMENT '向量库持久化路径或目录，SimpleVectorStore使用',
    vector_collection VARCHAR(200) DEFAULT NULL
        COMMENT '向量库集合/collection名称，Milvus/PGVector使用',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
        COMMENT '知识库状态 ACTIVE/INACTIVE/REBUILDING',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '是否删除 0-否, 1-是',
    INDEX idx_user_id(user_id),
    INDEX idx_user_status(user_id, status),
    INDEX idx_status(status),
    INDEX idx_vector_store_type(vector_store_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='知识库表';
```

**字段说明**

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|------|------|:----:|--------|------|
| id | BIGINT PK AI | 是 | - | 知识库主键 |
| user_id | BIGINT | 是 | - | 创建者，关联 `sys_user.id` |
| name | VARCHAR(100) | 是 | - | 知识库名称，如“招标项目A知识库” |
| description | VARCHAR(500) | 否 | NULL | 知识库描述 |
| vector_store_type | VARCHAR(50) | 是 | SIMPLE | 向量库类型枚举：SIMPLE / MILVUS / PGVECTOR |
| vector_store_path | VARCHAR(1000) | 否 | NULL | SimpleVectorStore 的持久化目录或文件前缀 |
| vector_collection | VARCHAR(200) | 否 | NULL | Milvus/PGVector 的 collection/table 名称 |
| status | VARCHAR(20) | 是 | ACTIVE | ACTIVE：可用；INACTIVE：停用；REBUILDING：重建中 |
| create_time | DATETIME | 是 | CURRENT_TIMESTAMP | MyMetaObjectHandler 自动填充 |
| update_time | DATETIME | 是 | CURRENT_TIMESTAMP ON UPDATE | 自动填充 |
| is_deleted | TINYINT | 是 | 0 | 逻辑删除标识 |

> 同一用户下知识库名称建议在应用层限制“未删除且 ACTIVE/INACTIVE 状态下不可重名”。如果后续需要数据库强约束，建议引入 `deleted_at` 或 `delete_version`，避免 `UNIQUE(user_id, name, is_deleted)` 阻塞多条历史删除记录。

### 3.3 `document_chunk` 文档 Chunk 表

```sql
CREATE TABLE IF NOT EXISTS document_chunk (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'Chunk数据库ID',
    chunk_id VARCHAR(200) NOT NULL
        COMMENT 'Chunk业务ID，如doc_1_chunk_0或doc_1_v2_chunk_0',
    document_id BIGINT NOT NULL COMMENT '文档ID，对应document_info.id',
    knowledge_id BIGINT NOT NULL COMMENT '知识库ID，对应knowledge_base.id',
    chunk_version INT NOT NULL DEFAULT 1
        COMMENT '切分版本，同一文档重新切分时递增',
    chunk_index INT NOT NULL COMMENT 'Chunk顺序，从0开始',
    total_chunks INT DEFAULT NULL COMMENT '文档总Chunk数',
    content TEXT NOT NULL COMMENT 'Chunk文本内容',
    content_hash VARCHAR(64) DEFAULT NULL COMMENT 'Chunk内容SHA-256，用于去重/校验',
    page_number INT DEFAULT NULL COMMENT 'Chunk所在PDF页码',
    chapter_index INT DEFAULT NULL COMMENT '章节序号',
    chapter_title VARCHAR(500) DEFAULT NULL COMMENT '章节标题',
    token_count INT DEFAULT NULL COMMENT 'Chunk Token数量',
    vector_id VARCHAR(200) DEFAULT NULL COMMENT '向量库中的向量ID或业务主键',
    metadata_json JSON DEFAULT NULL COMMENT '扩展元数据，如页码范围、坐标、解析器信息',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '是否删除 0-否, 1-是',
    UNIQUE KEY uk_chunk_id(chunk_id),
    UNIQUE KEY uk_doc_version_chunk(document_id, chunk_version, chunk_index),
    INDEX idx_document_id(document_id),
    INDEX idx_knowledge_id(knowledge_id),
    INDEX idx_knowledge_doc(knowledge_id, document_id),
    INDEX idx_document_chunk(document_id, chunk_version, chunk_index),
    INDEX idx_vector_id(vector_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='文档Chunk表';
```

**字段说明**

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|------|------|:----:|--------|------|
| id | BIGINT PK AI | 是 | - | Chunk 数据库主键 |
| chunk_id | VARCHAR(200) | 是 | - | 稳定业务 ID，要与 VectorStore metadata 中的 `chunkId` 对齐 |
| document_id | BIGINT | 是 | - | 所属文档，关联 `document_info.id` |
| knowledge_id | BIGINT | 是 | - | 所属知识库，冗余字段，用于隔离、过滤和统计 |
| chunk_version | INT | 是 | 1 | 文档重新切分时递增，避免新旧 chunk ID 冲突 |
| chunk_index | INT | 是 | - | Chunk 在当前版本文档内的顺序，从 0 开始 |
| total_chunks | INT | 否 | NULL | 当前切分版本的总 Chunk 数 |
| content | TEXT | 是 | - | Chunk 文本内容；TEXT 最大 64KB，单 Chunk 足够 |
| content_hash | VARCHAR(64) | 否 | NULL | `content` 的 SHA-256，便于重试、校验、去重 |
| page_number | INT | 否 | NULL | PDF 场景下的页码，便于溯源引用 |
| chapter_index | INT | 否 | NULL | 章节序号，与现有 `TextChunk.chapterIndex` 对齐 |
| chapter_title | VARCHAR(500) | 否 | NULL | 章节标题，与引用展示对齐 |
| token_count | INT | 否 | NULL | Token 数量，用于统计与截断策略调试 |
| vector_id | VARCHAR(200) | 否 | NULL | 向量库中的 ID；写入向量成功后必须回填 |
| metadata_json | JSON | 否 | NULL | 扩展元数据，如 `pageStart/pageEnd/bbox/parser` |
| create_time | DATETIME | 是 | CURRENT_TIMESTAMP | 自动填充 |
| update_time | DATETIME | 是 | CURRENT_TIMESTAMP ON UPDATE | 自动填充 |
| is_deleted | TINYINT | 是 | 0 | 逻辑删除标识 |

> `chunk_id` 建议规则：首次切分可兼容当前代码格式 `doc_{documentId}_chunk_{chunkIndex}`；如果支持同一文档多版本重切分，推荐使用 `doc_{documentId}_v{chunkVersion}_chunk_{chunkIndex}`。向量库 metadata、`RetrievedChunk.chunkId`、引用来源都应使用同一个值。

> **为什么 `content` 用 TEXT 而不是 LONGTEXT？**  
> 1GB 是原始 PDF 大小，不是单个 Chunk 的大小。一个 GB 级 PDF 会被切分为数千到数万个 Chunk，每个 Chunk 通常 200 到 1000 Token（约 1 到 3KB 文本），TEXT 类型（64KB 上限）通常足够。若实际业务允许超大段落不切分，应在切分服务中强制截断或拆分，而不是依赖 LONGTEXT。

### 3.4 `document_info` 表变更

在现有 `document_info` 表上追加 `knowledge_id` 字段，用于将文档归属到某个知识库：

```sql
ALTER TABLE document_info
    ADD COLUMN knowledge_id BIGINT DEFAULT NULL
        COMMENT '知识库ID，关联knowledge_base.id'
        AFTER id;

ALTER TABLE document_info
    ADD INDEX idx_knowledge_id(knowledge_id);

ALTER TABLE document_info
    ADD INDEX idx_user_knowledge(user_id, knowledge_id);

ALTER TABLE document_info
    ADD INDEX idx_process_status(process_status);
```

**变更后 `document_info` 核心字段**

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 文档 ID（已有） |
| knowledge_id | BIGINT, NULL, INDEX | 新增：所属知识库 ID |
| file_name / origin_file_name / file_url / storage_path / file_size / file_type | | 原有字段保持不变 |
| session_id / user_id / message_id | | 原有字段保持不变 |
| process_status / process_error | | 用于入库状态和失败补偿 |
| create_time / is_deleted | | 原有字段保持不变 |

> `knowledge_id` 允许为 NULL，用于兼容历史文档、会话临时上传和一次性问答上传。知识库文档上传接口应要求 `knowledge_id` 必填。

### 3.5 `chat_session` 表变更

RAG 对话场景下，会话必须知道“基于哪个知识库回答”。建议与知识库功能同步给 `chat_session` 增加 `knowledge_id` 字段：

```sql
ALTER TABLE chat_session
    ADD COLUMN knowledge_id BIGINT DEFAULT NULL
        COMMENT '绑定的知识库ID，RAG对话使用'
        AFTER model_name;

ALTER TABLE chat_session
    ADD INDEX idx_knowledge_id(knowledge_id);

ALTER TABLE chat_session
    ADD INDEX idx_user_knowledge(user_id, knowledge_id);
```

> 普通聊天会话可保持 `knowledge_id = NULL`。RAG 会话创建、发消息、继续会话时都要校验 `chat_session.knowledge_id` 是否属于当前用户。

---

## 4. 对应实体类设计（参考）

当前项目包名为 `org.grayray.aiassistant`，且已经拆分为 `ai-assistant-document`、`ai-assistant-rag-api`、`ai-assistant-rag-core` 等模块。以下包路径为建议，落地时以最终模块边界为准。

### 4.1 `KnowledgeBase.java`

```java
package org.grayray.aiassistant.knowledge.entity;

import com.baomidou.mybatisplus.annotation.*;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("knowledge_base")
@Schema(description = "知识库")
public class KnowledgeBase {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String name;

    private String description;

    private String vectorStoreType;

    private String vectorStorePath;

    private String vectorCollection;

    private String status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer isDeleted;
}
```

### 4.2 `DocumentChunk.java`

```java
package org.grayray.aiassistant.document.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@TableName(value = "document_chunk", autoResultMap = true)
@Schema(description = "文档Chunk")
public class DocumentChunk {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String chunkId;

    private Long documentId;

    private Long knowledgeId;

    private Integer chunkVersion;

    private Integer chunkIndex;

    private Integer totalChunks;

    private String content;

    private String contentHash;

    private Integer pageNumber;

    private Integer chapterIndex;

    private String chapterTitle;

    private Integer tokenCount;

    private String vectorId;

    @TableField(value = "metadata_json", typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> metadata;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer isDeleted;
}
```

### 4.3 `DocumentInfo.java` 字段追加

```java
/** 知识库ID（新增字段） */
private Long knowledgeId;
```

### 4.4 RAG API 模型字段追加

为了让知识库隔离在检索链路中生效，需要同步扩展 RAG API 模型：

| 类 | 需要新增/确认字段 | 说明 |
|----|------------------|------|
| `TextChunk` | `knowledgeId`、`chunkId`、`chunkVersion` | 切分结果进入 embedding 前就要带上知识库和业务 ID |
| `EmbeddedChunk` | 复用 `TextChunk` 中的 metadata | 向量写入时不要丢失 `knowledgeId` |
| `RetrievedChunk` | `knowledgeId`、`chunkId` | 检索结果用于过滤、引用和前端展示 |
| `VectorSearchRequest` | `knowledgeId` | RAG 会话检索必须传入 |
| `MetadataFilter` | 按 `knowledgeId` + `documentIds` 过滤 | SimpleVectorStore 后过滤；Milvus/PGVector 可下推 |

---

## 5. Mapper / Service 层约定

| 层 | 类名 | 路径建议 |
|----|------|---------|
| Mapper | `KnowledgeBaseMapper` | `ai-assistant-knowledge/.../mapper/KnowledgeBaseMapper.java` |
| Mapper | `DocumentChunkMapper` | `ai-assistant-document/.../mapper/DocumentChunkMapper.java` |
| Service 接口 | `KnowledgeBaseService` | `ai-assistant-knowledge/.../service/KnowledgeBaseService.java` |
| Service 接口 | `DocumentChunkService` | `ai-assistant-document/.../service/DocumentChunkService.java` |
| Service 实现 | `KnowledgeBaseServiceImpl` | `ai-assistant-knowledge/.../service/impl/KnowledgeBaseServiceImpl.java` |
| Service 实现 | `DocumentChunkServiceImpl` | `ai-assistant-document/.../service/impl/DocumentChunkServiceImpl.java` |

两个 Mapper 均继承 `BaseMapper<T>`，Service 均继承 `ServiceImpl<Mapper, T>` 并实现对应接口，`is_deleted` 由 `@TableLogic` 自动处理。

---

## 6. 文档入库流程（切分 → Embedding → 持久化）

```text
1. 用户上传文档到指定 knowledgeId
   ↓
2. 校验 knowledge_base 存在、ACTIVE、归属当前用户
   ↓
3. 保存 document_info
   - knowledge_id 填入库ID
   - process_status = pending
   ↓
4. 将 document_info 标记为 processing
   ↓
5. DocumentReader 解析原文
   - PDF: PagePdfDocumentReader / ParagraphPdfDocumentReader
   - Word/Txt: Tika 或自定义 Reader
   ↓
6. TextChunkService 切分
   - 生成 chunk_id / knowledge_id / document_id / chunk_version
   - 填充 chapter、page、token、metadata
   ↓
7. 批量写入 document_chunk
   - 同一 document_id + chunk_version + chunk_index 唯一
   - 写入 content_hash 便于重试校验
   ↓
8. EmbeddingModel 向量化
   ↓
9. VectorStore.add
   - metadata 必须包含 chunkId / knowledgeId / documentId / chunkIndex / totalChunks / chapterTitle
   - SimpleVectorStore 按知识库目录或全局目录+knowledgeId metadata 持久化
   - Milvus/PGVector 按 collection/table 和过滤字段写入
   ↓
10. 回写 document_chunk.vector_id
   ↓
11. document_info.process_status = completed
```

### 6.1 失败补偿策略

| 失败阶段 | 数据状态 | 补偿策略 |
|----------|----------|----------|
| 保存 `document_info` 前失败 | 无数据库记录 | 直接返回失败 |
| 解析/切分失败 | `document_info=processing`，无或少量 chunk | 标记 `failed`，写入 `process_error`，清理临时文件 |
| chunk 入库成功但 embedding 失败 | 有 chunk，无向量 | 标记 `failed`，保留 chunk 供重试，重试时按 `content_hash` 校验 |
| 向量写入成功但回写 `vector_id` 失败 | 有向量，chunk 未绑定向量 ID | 标记 `failed` 或 `partial_failed`，后台按 `chunk_id` 修复 |
| 重试同一文档 | 可能已有旧版本 chunk | 新增 `chunk_version`，成功后逻辑删除旧版本并删除旧向量 |

> `vector_id` 不建议作为“可选字段”长期为空。它可以在写入向量前短暂为空，但文档处理完成时必须已回填，或者至少保证 `chunk_id` 就是向量库业务主键。

### 6.2 SimpleVectorStore 持久化策略

当前项目已有 `VectorStorePersistenceManager` 按 `doc_{documentId}.json` 保存向量。引入知识库后有两种可选策略：

1. **按知识库分目录**：`vector_store_path = vector-store/kb_{knowledgeId}`，目录下继续按 `doc_{documentId}.json` 保存。优点是删除/迁移知识库简单。
2. **全局目录 + metadata 过滤**：继续使用全局 `ai.vector-store.persistence-path`，但每条向量 metadata 必须包含 `knowledgeId`。优点是改动较小，但删除知识库时需要按 metadata 清理。

推荐策略 1。若短期选择策略 2，文档中的 `knowledge_base.vector_store_path` 只能作为逻辑配置字段，必须在实现说明中明确当前不会被 `VectorStorePersistenceManager` 直接使用。

---

## 7. RAG 检索问答流程

```text
用户问题 + sessionId
        ↓
① 读取 chat_session，得到 userId / knowledgeId
        ↓
② 校验 knowledge_base 存在、ACTIVE、归属当前用户
        ↓
③ 构造 VectorSearchRequest
   - queries: 改写/扩展后的查询
   - knowledgeId: chat_session.knowledge_id
   - documentIds: 可选，用户限定文档范围时传入
        ↓
④ Embedding 用户问题
        ↓
⑤ VectorStore.similaritySearch
   - SimpleVectorStore: 召回后按 knowledgeId/documentIds 后过滤
   - Milvus/PGVector: 优先把 knowledgeId/documentIds 下推到向量查询
        ↓
⑥ 取回相似 Chunk
   - chunkId / knowledgeId / documentId / documentName
   - chapterTitle / pageNumber / content / score
        ↓
⑦ 组装 Prompt
   - 系统提示词
   - Context: 带引用编号的 Chunk 内容
   - 用户问题
        ↓
⑧ 调用 ChatModel（同步/SSE 流式）
        ↓
⑨ 保存 chat_message
   - assistant 消息写入 citations_json
        ↓
⑩ 返回答案和引用来源
```

> 检索入口不应直接相信前端传入的 `knowledgeId`。推荐从 `chat_session` 读取绑定的 `knowledge_id`，再校验用户权限。

---

## 8. 索引与性能说明

| 索引 | 用途 |
|------|------|
| `knowledge_base.idx_user_id` | 按用户查知识库 |
| `knowledge_base.idx_user_status` | 按用户查 ACTIVE/INACTIVE 知识库列表 |
| `knowledge_base.idx_status` | 后台按状态扫描 |
| `document_info.idx_knowledge_id` | 查询某知识库下所有文档 |
| `document_info.idx_user_knowledge` | 查询某用户某知识库下的文档 |
| `document_info.idx_process_status` | 后台扫描 pending/failed 文档 |
| `document_chunk.uk_chunk_id` | 通过业务 ID 定位 Chunk 和向量 |
| `document_chunk.uk_doc_version_chunk` | 防止同一文档同一版本重复 chunk |
| `document_chunk.idx_document_id` | 查询某文档下所有 Chunk |
| `document_chunk.idx_knowledge_doc` | 查询某知识库下某文档 Chunk |
| `document_chunk.idx_document_chunk` | 按文档顺序回放/调试 Chunk |
| `document_chunk.idx_vector_id` | 通过向量 ID 回查 Chunk |
| `chat_session.idx_user_knowledge` | 查询某用户某知识库下的 RAG 会话 |

> 向量相似度检索由 VectorStore 自身负责。MySQL 只存文本、业务 ID 和元数据，不做向量计算。

---

## 9. 数据一致性与清理策略

1. **删除知识库**：逻辑删除 `knowledge_base`，同时逻辑删除其下所有 `document_info` 和 `document_chunk`；异步删除或重建对应 VectorStore 文件/collection。
2. **删除文档**：逻辑删除 `document_info` 和关联 `document_chunk`；从 VectorStore 中按 `knowledgeId + documentId` 或 `chunkId` 删除对应向量。
3. **重新切分**：同一文档重新切分时，新建 `chunk_version + 1` 的 chunk；新版本向量全部写入并校验成功后，再逻辑删除旧版本 chunk 和旧向量。
4. **VectorStore 校验**：应用启动时校验持久化目录或 collection 是否存在。缺失时不要直接标记知识库不可用，优先标记为 `REBUILDING` 并尝试从 `document_chunk` 重建。
5. **孤儿数据修复**：后台任务定期检查“DB 有 chunk 无向量”“向量有 metadata 但 DB 无 active chunk”的不一致数据。
6. **权限一致性**：所有知识库、文档、会话查询都必须带 `user_id` 校验，不能只按 `knowledge_id` 查。

---

## 10. 与现有模块的集成点

| 现有模块 | 集成方式 |
|---------|---------|
| `sys_user` | `knowledge_base.user_id` 关联，用户只能看到自己的知识库 |
| `document_info` | 新增 `knowledge_id` 字段，知识库上传时必填 |
| `document_chunk` | 新增表，持久化 chunk 文本、业务 ID、章节、向量 ID 和 metadata |
| `TextChunk` | 新增 `knowledgeId/chunkId/chunkVersion`，并保持 `totalChunks/chapterTitle/chapterIndex` |
| `SimpleVectorStoreService` | metadata 增加 `knowledgeId`，持久化目录策略与 `knowledge_base.vector_store_path` 对齐 |
| `VectorSearchRequest` | 新增 `knowledgeId` |
| `MetadataFilter` | 支持按 `knowledgeId` 过滤，`documentIds` 作为二级范围限定 |
| `chat_session` | 新增 `knowledge_id`，标记该会话是基于哪个知识库的 RAG 对话 |
| `chat_message` | 继续使用 `citations_json` 存引用来源，可补充 `chunkId/pageNumber` |
| `GlobalExceptionHandler` | 新增“知识库不存在/无权限/向量库不可用”等业务异常 |
| `application.yaml` | 增加 `app.rag.chunk-size`、`app.rag.chunk-overlap`、`app.rag.top-k`、`ai.vector-store.persistence-path` 等配置 |

---

## 11. 实施顺序建议

1. **Step 1**：执行数据库迁移，创建 `knowledge_base`、`document_chunk`，扩展 `document_info` 和 `chat_session`。
2. **Step 2**：新增 Entity / Mapper / Service，并补充 `DocumentInfo.knowledgeId`。
3. **Step 3**：扩展 `TextChunk`、`RetrievedChunk`、`VectorSearchRequest`，让 `knowledgeId/chunkId/chunkVersion` 贯穿 RAG 链路。
4. **Step 4**：改造 `SimpleVectorStoreService`，写入 metadata 时增加 `knowledgeId`，并确定按知识库分目录或全局目录策略。
5. **Step 5**：实现知识库 CRUD 接口（创建/列表/详情/重命名/删除），所有接口校验 `user_id`。
6. **Step 6**：改造文档上传接口，支持指定 `knowledgeId`，上传后自动解析、切分、入库、向量化、回写 `vector_id`。
7. **Step 7**：扩展会话接口，创建 RAG 会话时绑定 `knowledgeId`；发送消息时从会话读取知识库并走 RAG 检索链路。
8. **Step 8**：补充后台修复任务，包括失败文档重试、孤儿向量清理、VectorStore 重建。
9. **Step 9**：后续切换到 Milvus/PGVector 时新增对应 VectorStore Bean 和 Factory 分支，复用 `chunk_id/knowledge_id/document_id` metadata。

---

## 附录 A：完整 DDL（可追加到迁移脚本）

> 说明：MySQL 8.0 的 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` / `ADD INDEX IF NOT EXISTS` 兼容性不稳定，不建议在通用迁移脚本中直接使用。推荐使用 Flyway/Liquibase 管理版本，或先查询 `information_schema` 后再执行 ALTER。以下 DDL 按“首次执行”编写。

```sql
-- =====================================================
-- 知识库表
-- =====================================================
CREATE TABLE IF NOT EXISTS knowledge_base (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '知识库ID',
    user_id BIGINT NOT NULL COMMENT '创建用户ID',
    name VARCHAR(100) NOT NULL COMMENT '知识库名称',
    description VARCHAR(500) DEFAULT NULL COMMENT '知识库描述',
    vector_store_type VARCHAR(50) NOT NULL DEFAULT 'SIMPLE'
        COMMENT '向量库类型 SIMPLE/MILVUS/PGVECTOR 等',
    vector_store_path VARCHAR(1000) DEFAULT NULL
        COMMENT '向量库持久化路径或目录，SimpleVectorStore使用',
    vector_collection VARCHAR(200) DEFAULT NULL
        COMMENT '向量库集合/collection名称，Milvus/PGVector使用',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
        COMMENT '知识库状态 ACTIVE/INACTIVE/REBUILDING',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '是否删除 0-否, 1-是',
    INDEX idx_user_id(user_id),
    INDEX idx_user_status(user_id, status),
    INDEX idx_status(status),
    INDEX idx_vector_store_type(vector_store_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库表';

-- =====================================================
-- 文档 Chunk 表
-- =====================================================
CREATE TABLE IF NOT EXISTS document_chunk (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'Chunk数据库ID',
    chunk_id VARCHAR(200) NOT NULL
        COMMENT 'Chunk业务ID，如doc_1_chunk_0或doc_1_v2_chunk_0',
    document_id BIGINT NOT NULL COMMENT '文档ID，对应document_info.id',
    knowledge_id BIGINT NOT NULL COMMENT '知识库ID，对应knowledge_base.id',
    chunk_version INT NOT NULL DEFAULT 1
        COMMENT '切分版本，同一文档重新切分时递增',
    chunk_index INT NOT NULL COMMENT 'Chunk顺序，从0开始',
    total_chunks INT DEFAULT NULL COMMENT '文档总Chunk数',
    content TEXT NOT NULL COMMENT 'Chunk文本内容',
    content_hash VARCHAR(64) DEFAULT NULL COMMENT 'Chunk内容SHA-256，用于去重/校验',
    page_number INT DEFAULT NULL COMMENT 'Chunk所在PDF页码',
    chapter_index INT DEFAULT NULL COMMENT '章节序号',
    chapter_title VARCHAR(500) DEFAULT NULL COMMENT '章节标题',
    token_count INT DEFAULT NULL COMMENT 'Chunk Token数量',
    vector_id VARCHAR(200) DEFAULT NULL COMMENT '向量库中的向量ID或业务主键',
    metadata_json JSON DEFAULT NULL COMMENT '扩展元数据，如页码范围、坐标、解析器信息',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '是否删除 0-否, 1-是',
    UNIQUE KEY uk_chunk_id(chunk_id),
    UNIQUE KEY uk_doc_version_chunk(document_id, chunk_version, chunk_index),
    INDEX idx_document_id(document_id),
    INDEX idx_knowledge_id(knowledge_id),
    INDEX idx_knowledge_doc(knowledge_id, document_id),
    INDEX idx_document_chunk(document_id, chunk_version, chunk_index),
    INDEX idx_vector_id(vector_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文档Chunk表';

-- =====================================================
-- document_info 增加 knowledge_id 字段
-- =====================================================
ALTER TABLE document_info
    ADD COLUMN knowledge_id BIGINT DEFAULT NULL
        COMMENT '知识库ID，关联knowledge_base.id'
        AFTER id;

ALTER TABLE document_info
    ADD INDEX idx_knowledge_id(knowledge_id);

ALTER TABLE document_info
    ADD INDEX idx_user_knowledge(user_id, knowledge_id);

ALTER TABLE document_info
    ADD INDEX idx_process_status(process_status);

-- =====================================================
-- chat_session 增加 knowledge_id 字段
-- =====================================================
ALTER TABLE chat_session
    ADD COLUMN knowledge_id BIGINT DEFAULT NULL
        COMMENT '绑定的知识库ID，RAG对话使用'
        AFTER model_name;

ALTER TABLE chat_session
    ADD INDEX idx_knowledge_id(knowledge_id);

ALTER TABLE chat_session
    ADD INDEX idx_user_knowledge(user_id, knowledge_id);
```

## 附录 B：迁移兼容建议

1. **已有历史文档**：`document_info.knowledge_id` 保持 NULL，不自动归入知识库。
2. **默认知识库**：如产品需要“所有文档默认可问”，可为每个用户创建默认知识库，再通过一次性迁移把历史文档归入默认知识库。
3. **向量重建**：上线后应提供按 `knowledge_id`、`document_id` 重建向量的后台任务。
4. **失败重试**：`process_status=failed` 的文档可重试，重试时生成新的 `chunk_version`。
5. **删除策略**：逻辑删除数据库记录后，向量文件/collection 删除建议异步执行，并记录失败日志供后台补偿。
