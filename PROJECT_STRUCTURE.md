# 项目结构说明

> 项目名称：**ai-assistant**（企业文档智能分析平台）
> 基于 Spring Boot 3 + Spring AI + React 的企业文档智能 RAG 问答平台，支持文档上传、向量化存储、智能问答、Query 路由、向量检索与重排等功能。

---

## 1. 整体架构

```
ai-assistant/
├── 📁 src/                          # 后端源码（Spring Boot，按业务域分包）
├── 📁 ai-assistant-frontend/        # 前端源码（React + Vite + TypeScript）
├── 📁 docs/                         # 设计文档（接口设计、系统分析）
├── 📁 upload/                       # 文件上传存储目录（运行时生成，按日期分子目录）
├── 📁 vector-store/                 # 向量数据 JSON 持久化目录（SimpleVectorStore，运行时生成）
├── 📁 target/                       # Maven 构建输出（构建时生成）
├── 📁 .mvn/                         # Maven Wrapper
├── 📁 .claude/                      # Claude Code 工作区配置
├── 📁 .idea/                        # IntelliJ IDEA 配置
├── 📄 pom.xml                       # Maven 项目配置
├── 📄 README.md                     # 项目说明文档
├── 📄 HELP.md                       # Spring Boot 帮助文档
├── 📄 PROJECT_STRUCTURE.md          # 项目结构说明（本文件）
├── 📄 mvnw / mvnw.cmd               # Maven Wrapper 启动脚本
└── 📄 .gitignore / .gitattributes   # Git 配置
```

---

## 2. 后端结构（Spring Boot）

后端采用**按业务域模块分包**的组织方式，每个业务域内部再按 controller / service / mapper / entity / dto / vo 分层。

### 2.1 顶层包结构

```
src/main/java/org/grayray/aiassistant/
├── AiAssistantApplication.java      # Spring Boot 启动类
├── chat/                            # 聊天会话域（会话管理、消息、SSE 流式输出）
├── common/                          # 通用公共组件（配置、异常、返回结果、填充器）
├── config/                          # 第三方中间件配置（向量数据库等）
├── document/                        # 文档域（上传、解析、清洗、分块）
├── rag/                             # RAG 核心域（Embedding、向量存储、路由、检索、重排）
└── user/                            # 用户域
```

### 2.2 各模块详细结构

#### 📦 `chat/` — 聊天会话域

```
chat/
├── controller/
│   └── ChatController.java          # REST API 入口（聊天发送、会话 CRUD、SSE 流式）
├── dto/                             # 前端请求入参（Jakarta Validation）
│   ├── ChatSendRequestDTO.java      # 发送消息请求
│   ├── ChatSessionCreateDTO.java    # 创建会话请求
│   ├── ChatSessionRenameDTO.java    # 重命名会话请求
│   └── ChatSessionDeleteDTO.java    # 删除会话请求
├── entity/                          # 数据库实体（@TableName）
│   ├── ChatMessage.java             # 聊天消息表
│   └── ChatSession.java             # 聊天会话表
├── mapper/                          # MyBatis-Plus Mapper
│   ├── ChatMessageMapper.java
│   └── ChatSessionMapper.java
├── service/
│   ├── ChatService.java             # 聊天服务接口（同步 + SSE 流式）
│   ├── ChatSessionService.java      # 会话管理服务接口
│   └── impl/
│       ├── ChatServiceImpl.java     # 聊天服务实现（编排 QueryRouter，SSE 输出）
│       └── ChatSessionServiceImpl.java
└── vo/                              # 返回前端的视图对象
    ├── ChatMessageVO.java           # 消息 VO
    ├── ChatSessionVO.java           # 会话 VO
    └── ChatStreamEvent.java         # SSE 流式事件
```

#### 📦 `common/` — 通用公共组件

```
common/
├── config/
│   ├── AsyncConfig.java             # 异步线程池配置
│   └── WebMvcConfig.java            # MVC 配置（静态资源映射、CORS 等）
├── exception/
│   ├── BusinessException.java       # 业务异常
│   └── GlobalExceptionHandler.java  # 全局异常处理器（@RestControllerAdvice）
├── handler/
│   └── MyMetaObjectHandler.java     # MyBatis-Plus 公共字段自动填充（createTime / updateTime）
└── result/
    ├── Result.java                  # 统一响应封装
    └── ResultCode.java              # 响应码枚举
```

#### 📦 `config/` — 中间件配置

```
config/
└── vector/
    ├── MilvusConfig.java            # Milvus 向量数据库配置（预留，当前已注释）
    └── MilvusProperties.java        # Milvus 配置属性类
```

#### 📦 `document/` — 文档域

```
document/
├── controller/
│   └── DocumentController.java      # 文档上传/查询 API
├── entity/
│   └── DocumentInfo.java            # 文档信息表实体
├── mapper/
│   └── DocumentInfoMapper.java
├── model/
│   └── TextChunk.java               # 文本分块业务对象
├── service/
│   ├── DocumentUploadService.java   # 文件上传处理接口
│   ├── DocumentProcessService.java  # 文档解析与流水线处理接口
│   ├── TextCleanService.java        # 文本清洗接口
│   ├── TextChunkService.java        # 文本分块接口
│   └── impl/
│       ├── DocumentUploadServiceImpl.java
│       ├── DocumentProcessServiceImpl.java  # 编排：解析 → 清洗 → 分块 → Embedding → 存储
│       ├── TextCleanServiceImpl.java
│       └── TextChunkServiceImpl.java
└── vo/
    └── DocumentUploadResult.java    # 文档上传结果 VO
```

#### 📦 `rag/` — RAG 核心域

```
rag/
├── model/                           # RAG 通用业务模型
│   ├── EmbeddedChunk.java           # 已向量化的文本块
│   ├── QueryRouteResult.java        # 路由结果
│   ├── QueryType.java               # 查询类型枚举（CHITCHAT / KB_QA 等）
│   ├── RewriteResult.java           # 查询改写结果
│   ├── ExpansionResult.java         # 查询扩展结果
│   └── RoutedQuery.java             # 路由后的查询对象
│
├── router/                          # 查询路由
│   ├── QueryRouterService.java      # 路由服务接口（分类 → 改写 → 扩展 编排）
│   ├── QueryRouterServiceImpl.java  # 路由服务实现
│   └── QueryClassifier.java         # 查询分类器（闲聊 vs 知识库问答）
│
├── rewrite/                         # 查询改写
│   ├── AbstractQueryComponent.java  # 查询处理抽象基类（模板方法模式）
│   └── QueryRewriter.java           # 查询改写器
│
├── expansion/                       # 查询扩展
│   └── QueryExpander.java           # 查询扩展器（多查询生成）
│
├── retrieval/                       # 向量检索与 TopK 召回
│   ├── VectorSearchService.java     # 向量检索服务接口
│   ├── VectorSearchServiceImpl.java # 向量检索实现（多查询合并 + 去重 + TopN）
│   ├── VectorSearchProperties.java  # 检索配置属性（topK/topN/minScore）
│   ├── VectorSearchRequest.java     # 检索请求
│   ├── VectorSearchResult.java      # 检索结果
│   ├── RetrievedChunk.java          # 召回的片段
│   └── filter/
│       └── MetadataFilter.java      # 元数据过滤器
│
├── rerank/                          # 重排（Rerank）
│   ├── RerankService.java           # 重排服务接口
│   ├── RerankConfig.java            # 重排配置类
│   ├── RerankProperties.java        # 重排配置属性
│   ├── RerankResult.java            # 重排结果
│   ├── RerankedChunk.java           # 重排后的片段
│   ├── client/
│   │   └── BgeRerankerClient.java   # bge-reranker HTTP 客户端
│   └── impl/
│       ├── BgeRerankServiceImpl.java # bge-reranker-v2-m3 重排实现
│       └── NoopRerankServiceImpl.java # 空实现（透传，rerank 关闭时使用）
│
└── service/                         # Embedding 与向量存储
    ├── EmbeddingService.java        # 向量化服务接口
    ├── VectorStoreService.java      # 向量存储服务接口
    └── impl/
        ├── EmbeddingServiceImpl.java
        ├── SimpleVectorStoreService.java        # Spring AI SimpleVectorStore 实现（当前使用）
        ├── MilvusVectorStoreService.java        # Milvus 实现（预留，已注释）
        └── VectorStorePersistenceManager.java   # 向量 JSON 持久化管理器
```

#### 📦 `user/` — 用户域

```
user/
├── controller/
│   └── SysUserController.java       # 用户 API
├── dto/
│   └── SysUserIdDTO.java            # 用户 ID 请求 DTO
├── entity/
│   └── SysUser.java                 # 系统用户表实体
├── mapper/
│   └── SysUserMapper.java
└── service/
    ├── SysUserService.java
    └── impl/
        └── SysUserServiceImpl.java
```

### 2.3 核心业务流水线

**文档处理流水线：**

```
DocumentUploadService (接收文件 → 本地存储)
        ↓
DocumentProcessService (解析 PDF，提取文本)
        ↓
TextCleanService (清洗：去空白、去页眉页脚等)
        ↓
TextChunkService (按策略分块)
        ↓
EmbeddingService (调用 Ollama bge-m3 向量化)
        ↓
VectorStoreService (写入 SimpleVectorStore + JSON 持久化)
```

**聊天问答流水线：**

```
ChatService (接收用户消息 → 保存)
        ↓
QueryRouterService
  ├─ QueryClassifier  (分类：闲聊 / 知识库问答)
  ├─ QueryRewriter    (改写：优化原始 query)
  └─ QueryExpander    (扩展：生成多路查询)
        ↓
VectorSearchService (向量检索：多路 TopK → 合并去重 → TopN)
        ↓
RerankService (可选：bge-reranker 重排，Noop 时透传)
        ↓
ChatService (拼接上下文 → 调用 DeepSeek → SSE 流式返回)
```

### 2.4 资源文件

```
src/main/resources/
├── application.yaml                 # 应用配置（端口、数据源、AI、检索、重排等）
├── db/
│   └── schema.sql                   # 数据库初始化 DDL
├── mapper/                          # MyBatis XML 映射（按域分子目录）
│   ├── document/
│   │   └── DocumentInfoMapper.xml
│   └── user/
│       └── SysUserMapper.xml
└── prompts/query/                   # Spring AI Prompt 模板（StringTemplate）
    ├── classify.st                  # 查询分类提示词
    ├── rewrite.st                   # 查询改写提示词
    └── expand.st                    # 查询扩展提示词
```

### 2.5 测试代码

```
src/test/java/org/grayray/aiassistant/
├── AiAssistantApplicationTests.java           # 应用上下文加载测试
├── chat/
│   └── ChatIntegrationTest.java               # 聊天集成测试
└── rag/
    ├── retrieval/
    │   └── VectorSearchServiceImplTest.java   # 向量检索测试
    └── router/
        └── QueryRouterServiceTest.java        # 查询路由测试
```

---

## 3. 前端结构（React + Vite + TypeScript）

### 3.1 目录结构

```
ai-assistant-frontend/
├── 📁 public/                        # 静态资源
│   ├── favicon.svg
│   └── icons.svg
├── 📁 src/
│   ├── main.tsx                      # 应用入口
│   ├── App.tsx                       # 根组件
│   ├── App.css / index.css           # 全局样式
│   ├── api/                          # API 请求封装
│   │   └── chat.ts                   # 聊天 & 会话相关 API
│   ├── components/                   # UI 组件（组件与同名 .css 并列放置）
│   │   ├── ChatDemo.tsx              # 演示用聊天组件
│   │   ├── ChatPage.tsx / .css       # 聊天页面主容器
│   │   ├── ChatWindow.tsx / .css     # 聊天窗口
│   │   ├── MessageList.tsx / .css    # 消息列表
│   │   ├── MessageInput.tsx / .css   # 消息输入框
│   │   ├── SessionSidebar.tsx / .css # 会话侧边栏
│   │   └── Markdown.tsx / .css       # Markdown 渲染组件
│   ├── hooks/                        # 自定义 Hooks
│   │   ├── useChatSession.ts         # 会话状态管理 Hook
│   │   └── useSSE.ts                 # SSE 流式接收 Hook
│   ├── types/                        # TypeScript 类型定义
│   │   ├── chat.ts                   # 聊天相关类型（消息、会话、事件）
│   │   └── env.d.ts                  # 环境变量类型声明
│   ├── utils/                        # 工具函数
│   │   ├── request.ts                # Axios 实例封装（拦截器、统一错误处理）
│   │   ├── sse.ts                    # SSE 连接工具（EventSource / fetch stream）
│   │   ├── config.ts                 # 配置常量（API base URL 等）
│   │   └── format.ts                 # 格式化工具（时间、消息等）
│   └── assets/                       # 静态资源（图片、SVG）
│       ├── hero.png
│       ├── react.svg
│       └── vite.svg
├── 📄 index.html                     # HTML 入口模板
├── 📄 package.json                   # 前端依赖与脚本
├── 📄 vite.config.ts                 # Vite 构建配置（含 API 代理）
├── 📄 tsconfig.json / tsconfig.app.json / tsconfig.node.json  # TypeScript 配置
├── 📄 .env.development / .env.production                     # 环境变量
├── 📄 .oxlintrc.json                 # oxlint 配置
├── 📄 .gitignore
├── 📄 README.md
└── 📄 MARKDOWN_IMPLEMENTATION_PLAN.md  # Markdown 渲染实现计划
```

### 3.2 前端技术栈

| 分类 | 技术 | 版本 |
|------|------|------|
| 框架 | React | ^19.2.8 |
| 语言 | TypeScript | ~6.0.2 |
| 构建工具 | Vite | ^8.2.0 |
| HTTP 客户端 | Axios | ^1.19.0 |
| Markdown 渲染 | react-markdown | ^10.1.0 |
| GFM 表格/任务列表 | remark-gfm | ^4.0.1 |
| 代码高亮 | rehype-highlight | ^7.0.2 |
| Lint | oxlint | ^1.75.0 |

---

## 4. 设计文档

```
docs/
├── 聊天会话接口设计文档.md             # 聊天会话 REST API 设计（同步 + SSE 流式）
├── 前端聊天接口对接文档.md             # 前端对接聊天接口的说明
├── 文件上传到向量持久化系统分析文档.md  # 文档上传 → 解析 → 向量化 → 持久化全链路分析
├── 知识库与文档Chunk表设计文档.md      # 知识库、文档、Chunk 数据库表设计
├── 向量检索与TopK召回系统分析文档.md   # 向量检索与 Top-K 召回策略分析
├── 向量检索与TopK召回设计文档.md       # 向量检索与 Top-K 召回策略设计
├── 重排Rerank设计文档.md               # Rerank 重排模块设计
├── Query路由处理设计文档.md            # Query 路由（分类/改写/扩展）设计
└── Query路由处理系统分析文档.md        # Query 路由系统整体分析
```

---

## 5. 技术栈总览

### 5.1 后端

| 层次 | 技术 | 版本 |
|------|------|------|
| 语言 | Java | 21 |
| 框架 | Spring Boot | 3.4.4 |
| AI 抽象 | Spring AI | 1.1.2 |
| 聊天模型 | DeepSeek（`spring-ai-starter-model-deepseek`） | deepseek-v4-flash |
| Embedding | Ollama（`spring-ai-starter-model-ollama`） | bge-m3（本地） |
| 向量存储 | Spring AI SimpleVectorStore（JSON 文件持久化） | — |
| 向量数据库（预留） | Milvus（milvus-sdk-java，当前已注释） | 2.4.5 |
| Rerank | bge-reranker-v2-m3（本地 Python 服务，可开关） | — |
| ORM | MyBatis-Plus（starter for Spring Boot 3） | 3.5.7 |
| 数据库 | MySQL | 8.x |
| PDF 解析 | Apache PDFBox | 3.0.5 |
| API 文档 | SpringDoc OpenAPI (Swagger UI) | 2.8.6 |
| 分布式事务 | Seata（starter 已引入，当前未使用） | 1.5.2 |
| 构建工具 | Maven（Wrapper） | 3.9.x |

### 5.2 前端

详见上方 3.2 节。

---

## 6. 数据库表（`schema.sql` 定义）

| 表名 | 说明 |
|------|------|
| `sys_user` | 系统用户表（id / userName / 时间戳 / 逻辑删除） |
| `chat_session` | 聊天会话表（sessionId / userId / title / sessionType / modelName / summary / 时间戳 / 逻辑删除） |
| `chat_message` | 聊天消息表（sessionId / userId / role / content / messageIndex / modelName / finishReason / 时间戳 / 逻辑删除） |
| `document_info` | 文档信息表（文件名 / 路径 / 类型 / 大小 / 会话用户消息关联 / processStatus / processError / 时间戳 / 逻辑删除） |

> 注：Chunk 数据当前通过 SimpleVectorStore 以 JSON 文件形式持久化（`vector-store/` 目录），未建数据库表。详细设计见 `docs/知识库与文档Chunk表设计文档.md`。

---

## 7. 运行时目录

| 目录 | 说明 | 配置项 |
|------|------|--------|
| `upload/` | 文件上传本地存储目录，按日期分分子目录（如 `upload/2026-08-07/xxx.pdf`） | `spring.servlet.multipart.*` + `WebMvcConfig` 静态映射 |
| `vector-store/` | 向量数据 JSON 持久化目录（SimpleVectorStore） | `ai.vector-store.persistence-path=./vector-store` |
| `target/` | Maven 编译输出与测试报告 | Maven 默认 |

---

## 8. 端口与外部服务

| 服务 | 端口 | 说明 |
|------|------|------|
| 后端 API | **8900** | `server.port=8900` |
| 前端开发服务器 | **5173** | Vite dev server，API 请求代理到 8900 |
| Swagger UI | 8900 | http://localhost:8900/swagger-ui.html |
| Ollama（Embedding） | **11434** | 本地 Embedding 模型服务，需提前 `ollama pull bge-m3` |
| Rerank 服务（可选） | **8000** | bge-reranker-v2-m3 Python FastAPI 服务；`ai.rerank.enabled=false` 时不需要 |
| Milvus（预留） | 19530 | 当前未启用，启用前需取消注释相关配置与代码 |
