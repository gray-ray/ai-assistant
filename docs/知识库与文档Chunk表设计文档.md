# 知识库与文档 Chunk 表设计文档

> 版本：v1.0
> 日期：2026-08-08
> 项目：ai-assistant（Spring Boot 3.4 + Spring AI 1.1）

---

## 1. 概述

### 1.1 目标

基于已有的 `sys_user`、`chat_session`、`chat_message`、`document_info` 四张表，新增 **知识库（knowledge_base）** 和 **文档 Chunk（document_chunk）** 两张表，并对 `document_info` 做小幅扩展，形成完整的 RAG（Retrieval-Augmented Generation）知识库存储结构，支撑：

- 用户创建/管理多个知识库（如按项目、按招标任务隔离）
- 文档（PDF/Word/TXT 等）上传后解析、切分 Chunk、Embedding 向量化
- Chunk 文本与向量 ID 持久化，便于从 `SimpleVectorStore` 切换到 Milvus / PGVector 时保持结构不变
- 会话绑定知识库，实现"针对某个知识库问答"的 RAG 聊天

### 1.2 设计原则

1. **与现有项目风格保持一致**：沿用 `id BIGINT AUTO_INCREMENT`、`is_deleted` 逻辑删除、`create_time` / `update_time` 自动填充、`idx_` 前缀索引、中文 COMMENT 等约定。
2. **向量库可插拔**：`knowledge_base.vector_store_type` 标识向量库类型（SIMPLE / MILVUS / PGVECTOR），未来切换时表结构基本不动。
3. **Chunk 粒度存储**：单个 PDF 可能很大（GB 级），但单个 Chunk 通常几百 ~ 几千 Token，因此 `document_chunk.content` 使用 `TEXT`（最大 64KB），不使用 `LONGTEXT`。
4. **三层关系清晰**：`knowledge_base` → `document_info` → `document_chunk`，一层一层向下展开。

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
   │                                                       │
   │                                                       ↓
   │                                                  SimpleVectorStore
   │                                                       (JSON)
   │
   └── 1:N ──→ chat_session ── knowledge_id ──→ knowledge_base
                     │
                     └── 1:N ──→ chat_message
```

完整链路：

```text
                         sys_user
                         /      \
                        ↓        ↓
               knowledge_base   chat_session
                    │                │
                    ↓                ↓ (knowledge_id)
              document_info    chat_message
                    │
                    ↓
             document_chunk
                    │
                    ↓
             SimpleVectorStore
                    │
                    ↓
              向量检索 → RAG 回答
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
        COMMENT '向量库持久化路径，SimpleVectorStore使用',

    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
        COMMENT '知识库状态 ACTIVE/INACTIVE',

    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
        COMMENT '创建时间',

    update_time DATETIME DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP
        COMMENT '更新时间',

    is_deleted TINYINT NOT NULL DEFAULT 0
        COMMENT '是否删除 0-否, 1-是',

    INDEX idx_user_id(user_id),
    INDEX idx_status(status)

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='知识库表';
```

**字段说明**

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|------|------|:----:|--------|------|
| id | BIGINT PK AI | 是 | — | 知识库主键 |
| user_id | BIGINT | 是 | — | 创建者，关联 `sys_user.id` |
| name | VARCHAR(100) | 是 | — | 知识库名称，如"招标项目A知识库" |
| description | VARCHAR(500) | 否 | NULL | 知识库描述 |
| vector_store_type | VARCHAR(50) | 是 | 'SIMPLE' | 向量库类型枚举：SIMPLE / MILVUS / PGVECTOR |
| vector_store_path | VARCHAR(1000) | 否 | NULL | 向量持久化路径；SIMPLE 类型存 JSON 文件相对路径，如 `vector-store/kb_1.json` |
| status | VARCHAR(20) | 是 | 'ACTIVE' | ACTIVE：可用；INACTIVE：已停用 |
| create_time | DATETIME | 是 | CURRENT_TIMESTAMP | MyMetaObjectHandler 自动填充 |
| update_time | DATETIME | 是 | CURRENT_TIMESTAMP ON UPDATE | 自动填充 |
| is_deleted | TINYINT | 是 | 0 | 逻辑删除标识 |

**示例数据**

| id | user_id | name | description | vector_store_type | vector_store_path | status |
|---:|--------:|------|-------------|-------------------|-------------------|--------|
| 1 | 1 | 招标项目A知识库 | 招标项目A相关文档 | SIMPLE | vector-store/kb_1.json | ACTIVE |
| 2 | 1 | 招标项目B知识库 | 招标项目B相关文档 | SIMPLE | vector-store/kb_2.json | ACTIVE |

---

### 3.3 `document_chunk` 文档 Chunk 表

```sql
CREATE TABLE IF NOT EXISTS document_chunk (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'Chunk ID',

    document_id BIGINT NOT NULL COMMENT '文档ID，对应document_info.id',

    knowledge_id BIGINT NOT NULL COMMENT '知识库ID，对应knowledge_base.id',

    chunk_index INT NOT NULL COMMENT 'Chunk顺序，从0开始',

    content TEXT NOT NULL COMMENT 'Chunk文本内容',

    page_number INT DEFAULT NULL COMMENT 'Chunk所在PDF页码',

    token_count INT DEFAULT NULL COMMENT 'Chunk Token数量',

    vector_id VARCHAR(200) DEFAULT NULL COMMENT '向量库中的向量ID',

    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
        COMMENT '创建时间',

    is_deleted TINYINT NOT NULL DEFAULT 0
        COMMENT '是否删除 0-否, 1-是',

    INDEX idx_document_id(document_id),
    INDEX idx_knowledge_id(knowledge_id),
    INDEX idx_document_chunk(document_id, chunk_index)

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='文档Chunk表';
```

**字段说明**

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|------|------|:----:|--------|------|
| id | BIGINT PK AI | 是 | — | Chunk 主键 |
| document_id | BIGINT | 是 | — | 所属文档，关联 `document_info.id` |
| knowledge_id | BIGINT | 是 | — | 所属知识库，关联 `knowledge_base.id`（冗余字段，方便跨文档查询） |
| chunk_index | INT | 是 | — | Chunk 在文档内的顺序，从 0 开始 |
| content | TEXT | 是 | — | Chunk 文本内容；TEXT 最大 64KB，单 Chunk 足够 |
| page_number | INT | 否 | NULL | PDF 场景下的页码，便于溯源引用 |
| token_count | INT | 否 | NULL | Token 数量，用于统计与截断策略调试 |
| vector_id | VARCHAR(200) | 否 | NULL | 向量库中的向量 ID；SimpleVectorStore 下可以为 NULL 或存储 JSON 内的 key，Milvus 下存其主键 |
| create_time | DATETIME | 是 | CURRENT_TIMESTAMP | 自动填充 |
| is_deleted | TINYINT | 是 | 0 | 逻辑删除标识 |

> **为什么 `content` 用 TEXT 而不是 LONGTEXT？**
> 1GB 是原始 PDF 大小，不是单个 Chunk 的大小。一个 GB 级 PDF 会被切分为数千~数万个 Chunk，每个 Chunk 通常 200~1000 Token（约 1~3KB 文本），TEXT 类型（64KB 上限）完全足够。使用 TEXT 可以避免行溢出，查询性能更好。

**切分示意**

```text
1GB PDF
 ↓
PagePdfDocumentReader 解析
 ↓
TokenTextSplitter 切分
 ↓
Chunk 0 (page 1) → document_chunk (content=TEXT, token_count=xxx)
Chunk 1 (page 1) → document_chunk
Chunk 2 (page 2) → document_chunk
...
Chunk N (page M) → document_chunk
 ↓
EmbeddingModel 向量化
 ↓
VectorStore.add(List<Document>) → 同时写入 JSON 文件
```

---

### 3.4 `document_info` 表变更

在现有 `document_info` 表上追加 `knowledge_id` 字段，用于将文档归属到某个知识库：

```sql
ALTER TABLE document_info
    ADD COLUMN knowledge_id BIGINT DEFAULT NULL
        COMMENT '知识库ID，关联knowledge_base.id'
        AFTER id;

ALTER TABLE document_info
    ADD INDEX idx_knowledge_id(knowledge_id);
```

**变更后 `document_info` 核心字段**

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 文档 ID（已有） |
| **knowledge_id** | BIGINT, NULL, INDEX | **新增**：所属知识库 ID |
| file_name / original_name / file_path / file_size / file_type / ... | | 原有字段保持不变 |
| create_time / update_time / is_deleted | | 原有字段保持不变 |

> 说明：`knowledge_id` 允许为 NULL，以兼容未归入知识库的独立文档（历史数据、一次性问答上传等）。

### 3.5 `chat_session` 表变更（可选，后续迭代）

RAG 对话场景下，会话需要知道"基于哪个知识库回答"。建议在后续迭代中给 `chat_session` 增加 `knowledge_id` 字段（本文不强制，可在 RAG 接口落地时一起做）：

```sql
ALTER TABLE chat_session
    ADD COLUMN knowledge_id BIGINT DEFAULT NULL
        COMMENT '绑定的知识库ID，RAG对话使用'
        AFTER model_name;

ALTER TABLE chat_session
    ADD INDEX idx_knowledge_id(knowledge_id);
```

---

## 4. 对应实体类设计（参考）

与项目现有的 `SysUser`、`ChatSession`、`ChatMessage`、`DocumentInfo` 风格一致，使用 MyBatis-Plus + Lombok。

### 4.1 `KnowledgeBase.java`

```java
package com.ai.assistant.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("knowledge_base")
public class KnowledgeBase {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String name;

    private String description;

    private String vectorStoreType;

    private String vectorStorePath;

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
package com.ai.assistant.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("document_chunk")
public class DocumentChunk {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long documentId;

    private Long knowledgeId;

    private Integer chunkIndex;

    private String content;

    private Integer pageNumber;

    private Integer tokenCount;

    private String vectorId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableLogic
    private Integer isDeleted;
}
```

### 4.3 `DocumentInfo.java` 字段追加

```java
/** 知识库ID（新增字段） */
private Long knowledgeId;
```

---

## 5. Mapper / Service 层约定

与现有风格一致（参考 `ChatSessionMapper` / `ChatSessionServiceImpl`）：

| 层 | 类名 | 路径建议 |
|----|------|---------|
| Mapper | `KnowledgeBaseMapper` | `mapper/KnowledgeBaseMapper.java` |
| Mapper | `DocumentChunkMapper` | `mapper/DocumentChunkMapper.java` |
| Service 接口 | `KnowledgeBaseService` | `service/KnowledgeBaseService.java` |
| Service 接口 | `DocumentChunkService` | `service/DocumentChunkService.java` |
| Service 实现 | `KnowledgeBaseServiceImpl` | `service/impl/KnowledgeBaseServiceImpl.java` |
| Service 实现 | `DocumentChunkServiceImpl` | `service/impl/DocumentChunkServiceImpl.java` |

两个 Mapper 均继承 `BaseMapper<T>`，Service 均继承 `ServiceImpl<Mapper, T>` 并实现对应接口，`is_deleted` 由 `@TableLogic` 自动处理。

---

## 6. 文档入库流程（切分 → Embedding → 持久化）

```text
┌──────────────────────────────────────────────────────────────┐
│ 1. 用户上传文档（PDF/Word/TXT）到指定知识库 knowledgeId       │
└──────────────────────────────────────────────────────────────┘
                              ↓
┌──────────────────────────────────────────────────────────────┐
│ 2. 保存 document_info 记录（knowledge_id 填入库ID）           │
└──────────────────────────────────────────────────────────────┘
                              ↓
┌──────────────────────────────────────────────────────────────┐
│ 3. 使用 Spring AI DocumentReader 解析                        │
│    - PDF: PagePdfDocumentReader / ParagraphPdfDocumentReader │
│    - Word/Txt: 自定义或 Tika DocumentReader                   │
└──────────────────────────────────────────────────────────────┘
                              ↓
┌──────────────────────────────────────────────────────────────┐
│ 4. 使用 TokenTextSplitter 切分为 Chunk 列表（List<Document>） │
│    - 默认 chunkSize=500, overlap=50（可配置）                 │
│    - 为每个 Document 填充 metadata: docId/kbId/pageNumber     │
└──────────────────────────────────────────────────────────────┘
                              ↓
┌──────────────────────────────────────────────────────────────┐
│ 5. 批量写入 document_chunk 表                                │
│    - 每个 Document → 一条 document_chunk                      │
│    - chunk_index 从 0 递增                                    │
└──────────────────────────────────────────────────────────────┘
                              ↓
┌──────────────────────────────────────────────────────────────┐
│ 6. 调用 EmbeddingModel 向量化 → VectorStore.add(documents)   │
│    - SimpleVectorStore: 持久化到 vector_store_path 指向的 JSON│
│    - 未来 Milvus/PGVector: 写入对应向量服务                   │
└──────────────────────────────────────────────────────────────┘
                              ↓
┌──────────────────────────────────────────────────────────────┐
│ 7. （可选）回写 vector_id，建立 Chunk ↔ 向量的双向映射        │
└──────────────────────────────────────────────────────────────┘
```

---

## 7. RAG 检索问答流程

```text
用户问题 + knowledge_id
        ↓
① 从 knowledge_base 读取 vector_store_path / vector_store_type
        ↓
② 加载对应 VectorStore（SIMPLE 则从 JSON 文件反序列化）
        ↓
③ Embedding 用户问题 → VectorStore.similaritySearch(query, topK=5)
        ↓
④ 取回相似 Chunk（带 documentId / pageNumber）
        ↓
⑤ 组装 Prompt：
   - 系统提示词（你是招标助手...）
   - Context: [Chunk 0 content][Chunk 1 content]...
   - 用户问题
        ↓
⑥ 调用 DeepSeek ChatModel（同步/SSE 流式）
        ↓
⑦ 保存 chat_message（可选附带来源 documentId/pageNumber 用于引用高亮）
        ↓
⑧ 返回答案
```

---

## 8. 索引与性能说明

| 索引 | 用途 |
|------|------|
| `knowledge_base.idx_user_id` | 按用户查知识库列表 |
| `knowledge_base.idx_status` | 按状态过滤（只查 ACTIVE） |
| `document_chunk.idx_document_id` | 查询某文档下所有 Chunk（如删除文档时级联清理） |
| `document_chunk.idx_knowledge_id` | 跨文档按知识库维度查询 Chunk（后台管理、统计） |
| `document_chunk.idx_document_chunk(document_id, chunk_index)` | 按文档顺序取 Chunk（回放/调试） |
| `document_info.idx_knowledge_id`（新增） | 查询某知识库下所有文档 |

> 向量相似度检索由 VectorStore 自身负责（SimpleVectorStore 做内存 cosine；Milvus/PGVector 由专用索引负责），MySQL 只存文本元数据，不做向量计算。

---

## 9. 数据一致性与清理策略

1. **删除知识库**：逻辑删除 `knowledge_base`，同时逻辑删除其下所有 `document_info` 和 `document_chunk`；对应 VectorStore 文件（JSON）可异步物理删除。
2. **删除文档**：逻辑删除 `document_info` 及关联 `document_chunk`；从 VectorStore 中按 metadata.docId 删除对应向量（SimpleVectorStore 需重建或过滤；Milvus 可直接按 ID 删）。
3. **重新切分**：同一文档重新上传/重新切分时，先把旧 `document_chunk` 逻辑删除并从 VectorStore 移除旧向量，再写入新的 Chunk。
4. **VectorStore 校验**：应用启动时可校验 `knowledge_base.vector_store_path` 指向的文件是否存在，不存在则标记 INACTIVE 或触发重建。

---

## 10. 与现有模块的集成点

| 现有模块 | 集成方式 |
|---------|---------|
| `sys_user` | `knowledge_base.user_id` 关联，用户只能看到自己的知识库 |
| `document_info` | 新增 `knowledge_id` 字段，上传文档时必须选择知识库（或走默认知识库） |
| `chat_session` | 后续迭代新增 `knowledge_id`，标记该会话是基于哪个知识库的 RAG 对话 |
| `chat_message` | 存问答记录，未来可扩展 `source_chunks` 字段存储引用来源 |
| `GlobalExceptionHandler` | 新增"知识库不存在/无权限"等业务异常 |
| `MyMetaObjectHandler` | 自动填充 `create_time` / `update_time`，无需改动 |
| `application.yaml` | 可在 `app.rag` 下添加默认切分参数：`chunk-size`、`chunk-overlap`、`top-k`、`vector-store-root` 等 |

---

## 11. 实施顺序建议

1. **Step 1**：执行 DDL（建 `knowledge_base`、`document_chunk`；ALTER `document_info`）。
2. **Step 2**：生成 Entity / Mapper / Service 代码（可用 MyBatis-Plus 代码生成器或手写）。
3. **Step 3**：实现知识库 CRUD 接口（创建/列表/详情/重命名/删除）。
4. **Step 4**：改造文档上传接口，支持指定 `knowledgeId`，上传后自动解析 → 切分 → 入库 → 向量化（参考 [[文件上传到向量持久化系统分析文档]]）。
5. **Step 5**：封装 `VectorStoreFactory`，根据 `knowledge_base.vector_store_type` 和 `vector_store_path` 加载/创建 VectorStore 实例。
6. **Step 6**：扩展会话接口，支持在创建会话时绑定 `knowledgeId`；在发送消息时走 RAG 检索链路。
7. **Step 7**：（后续）从 SimpleVectorStore 切换到 Milvus/PGVector，只需新增对应 VectorStore Bean 和 Factory 分支，表结构无需变动。

---

## 附录 A：完整 DDL（可直接追加到 `schema.sql`）

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
        COMMENT '向量库持久化路径，SimpleVectorStore使用',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
        COMMENT '知识库状态 ACTIVE/INACTIVE',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '是否删除 0-否, 1-是',
    INDEX idx_user_id(user_id),
    INDEX idx_status(status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库表';

-- =====================================================
-- 文档 Chunk 表
-- =====================================================
CREATE TABLE IF NOT EXISTS document_chunk (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'Chunk ID',
    document_id BIGINT NOT NULL COMMENT '文档ID，对应document_info.id',
    knowledge_id BIGINT NOT NULL COMMENT '知识库ID，对应knowledge_base.id',
    chunk_index INT NOT NULL COMMENT 'Chunk顺序，从0开始',
    content TEXT NOT NULL COMMENT 'Chunk文本内容',
    page_number INT DEFAULT NULL COMMENT 'Chunk所在PDF页码',
    token_count INT DEFAULT NULL COMMENT 'Chunk Token数量',
    vector_id VARCHAR(200) DEFAULT NULL COMMENT '向量库中的向量ID',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '是否删除 0-否, 1-是',
    INDEX idx_document_id(document_id),
    INDEX idx_knowledge_id(knowledge_id),
    INDEX idx_document_chunk(document_id, chunk_index)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文档Chunk表';

-- =====================================================
-- document_info 增加 knowledge_id 字段
-- =====================================================
ALTER TABLE document_info
    ADD COLUMN IF NOT EXISTS knowledge_id BIGINT DEFAULT NULL
        COMMENT '知识库ID，关联knowledge_base.id'
        AFTER id;
ALTER TABLE document_info
    ADD INDEX IF NOT EXISTS idx_knowledge_id(knowledge_id);
```

> 注：`ADD COLUMN IF NOT EXISTS` / `ADD INDEX IF NOT EXISTS` 在 MySQL 8.0 中可用；若版本较老可去掉 `IF NOT EXISTS` 并人工判断是否已存在。
