# AI Assistant

基于 Spring Boot 3 + Spring AI + React 的AI助手平台。支持文档上传解析、向量化存储、智能检索、多轮对话问答、引用溯源与事实校验等完整 RAG 能力。

## ✨ 功能特性

### 文档处理
- **多格式上传**：支持 PDF 等多种文档格式，单文件最大 500MB
- **异步解析**：上传即返回，后台异步完成 PDF 解析、文本清洗、分块、向量化、持久化全流程
- **向量持久化**：基于 Spring AI SimpleVectorStore + JSON 文件持久化，重启数据不丢失

### 智能问答（RAG）
- **Query Router**：智能分类查询类型（简单 / 上下文依赖 / 复杂问题），自动改写或扩展查询
- **多查询向量检索**：多路召回 + 合并去重 + TopN 筛选，支持元数据过滤与最低相似度阈值
- **Rerank 重排**：基于 bge-reranker-v2-m3 交叉编码器精排，支持向量得分融合
- **上下文组装**：Token 预算截断 + 多格式输出（编号引用 / Markdown / 纯文本）
- **流式输出**：SSE 逐字流式回复，支持中途停止生成
- **Groundedness 事实校验**：生成后 LLM 校验回答是否被原文支撑，未支撑语句标注 ⚠️

### 会话管理
- **多会话**：会话创建、重命名、删除（逻辑删除）、历史消息回溯
- **自动命名**：首条消息自动生成会话标题
- **消息持久化**：完整的聊天消息历史记录

### 工程化
- **MyBatis-Plus**：逻辑删除、自动填充、分页插件
- **统一异常处理**：全局 `Result<T>` 响应封装
- **Swagger / OpenAPI**：完整 API 文档（`/swagger-ui.html`）
- **前端工程**：React 19 + TypeScript 6 + Vite 8 + Axios + react-markdown

---

## 🏗️ 系统架构

```
┌─────────────────────────────────────────────────────────────┐
│                        前端 (React 19)                      │
│  SessionSidebar | ChatWindow | MessageList | Markdown 渲染   │
└──────────────┬───────────────────────┬──────────────────────┘
               │ REST / SSE            │
               ▼                       ▼
┌─────────────────────────────────────────────────────────────┐
│                   后端 (Spring Boot 3.4)                     │
│  ┌──────────┐  ┌────────────┐  ┌─────────────────────────┐  │
│  │ 文档上传  │  │  会话管理   │  │   聊天 / RAG 流水线      │  │
│  │ 异步解析  │  │  消息持久化  │  │ Router→检索→重排→组装→生成 │  │
│  └─────┬────┘  └─────┬──────┘  └───────────┬─────────────┘  │
│        │             │                      │                │
│        ▼             ▼                      ▼                │
│   MySQL (MyBatis-Plus)        SimpleVectorStore (JSON)       │
└───────────────┬───────────────────────┬──────────────────────┘
                │                       │
                ▼                       ▼
         ┌──────────┐          ┌──────────────────┐
         │ DeepSeek │          │ Ollama (bge-m3)  │
         │  Chat API │          │ Embedding 模型   │
         └──────────┘          └──────────────────┘
                                        ▲
                                ┌───────┴───────┐
                                │ bge-reranker  │
                                │ (Python 服务)  │
                                └───────────────┘
```

### 技术栈

| 层级 | 技术 | 版本 |
|------|------|------|
| 后端框架 | Spring Boot | 3.4.4 |
| AI 框架 | Spring AI | 1.1.2 |
| 聊天模型 | DeepSeek | deepseek-v4-flash |
| 嵌入模型 | Ollama / bge-m3 | 1024 维 |
| 重排模型 | bge-reranker-v2-m3 | 外部 Python 服务 |
| ORM | MyBatis-Plus | 3.5.7 |
| 数据库 | MySQL | 8.0+ |
| PDF 解析 | Apache PDFBox | 3.0.5 |
| API 文档 | SpringDoc OpenAPI | 2.8.6 |
| 前端框架 | React | 19.2.8 |
| 前端构建 | Vite | 8.2.0 |
| 语言 | TypeScript | 6.0.2 |
| HTTP 客户端 | Axios | 1.19.0 |
| Markdown 渲染 | react-markdown | 10.1.0 |

---

## 📁 目录结构

```
ai-assistant/
├── pom.xml                                 # Maven 父工程
├── ai-assistant-common/                    # 通用响应、异常、配置
├── ai-assistant-rag-api/                   # RAG 对外契约和模型
├── ai-assistant-rag-core/                  # RAG 实现、Prompt、向量存储适配
├── ai-assistant-user/                      # 用户模块
├── ai-assistant-document/                  # 文档上传、解析、清洗、分块
├── ai-assistant-chat/                      # 聊天会话、消息、SSE、RAG 编排
├── ai-assistant-server/                    # 启动类、应用配置、SQL、最终打包
├── ai-assistant-frontend/                  # 前端源码（React + Vite）
│   ├── src/
│   │   ├── api/                            # API 层
│   │   ├── components/                     # 组件
│   │   ├── hooks/                          # 自定义 Hook
│   │   ├── utils/                          # 工具函数
│   │   └── pages/                          # 页面
│   └── vite.config.ts                      # Vite 配置
├── docs/                                   # 详细设计文档
├── upload/                                 # 文件上传存储目录（运行时）
├── vector-store/                           # 向量数据持久化目录（运行时）
└── PROJECT_STRUCTURE.md                    # 项目结构详解
```

---

## 🔧 环境要求

| 组件 | 版本要求 | 说明 |
|------|---------|------|
| JDK | 21+ | Spring Boot 3.4 要求 Java 21 |
| Maven | 3.8+ | 项目使用 Maven 构建 |
| MySQL | 8.0+ | 关系型数据库 |
| Node.js | 18+ | 前端构建运行时 |
| Ollama | 最新版 | 本地 Embedding 模型服务 |
| Python (可选) | 3.10+ | Rerank 重排服务（可选，不启用则跳过重排） |

---

## ▶️ 如何运行项目

项目需要先启动后端依赖，再分别运行后端和前端。推荐按下面顺序操作：

### 1. 准备 MySQL

确保 MySQL 8.0+ 已启动，并在项目根目录执行初始化 SQL：

```bash
mysql -u root -p < ai-assistant-server/src/main/resources/db/schema.sql
```

默认连接配置在 `ai-assistant-server/src/main/resources/application.yaml`：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/ai_assistant
    username: root
    password: 123456
```

如果本地 MySQL 用户名或密码不同，请先修改 `application.yaml`。

### 2. 准备 Ollama Embedding 模型

后端启动时会连接本地 Ollama，并使用 `bge-m3` 做向量化。请先安装 Ollama，然后执行：

```bash
ollama pull bge-m3
ollama list
```

确认 Ollama 服务运行在 `http://localhost:11434`。

### 3. 配置 DeepSeek API Key

打开 `ai-assistant-server/src/main/resources/application.yaml`，将 `spring.ai.deepseek.api-key` 修改为你自己的 DeepSeek API Key。

### 4. 启动后端

在项目根目录执行：

```bash
# Windows PowerShell
.\mvnw.cmd -pl ai-assistant-server -am spring-boot:run

# macOS / Linux
./mvnw -pl ai-assistant-server -am spring-boot:run
```

后端默认运行在：

- API 服务：http://localhost:8900
- Swagger 文档：http://localhost:8900/swagger-ui.html

### 5. 启动前端

新开一个终端，执行：

```bash
cd ai-assistant-frontend
npm install
npm run dev
```

前端默认运行在：http://localhost:5173

开发环境下，前端会通过 Vite 代理把 `/api` 请求转发到 `http://localhost:8900`。如需修改后端地址，可设置 `VITE_PROXY_TARGET`。

### 6. 可选：启动 Rerank 服务

当前 `application.yaml` 中 `ai.rerank.enabled` 默认为 `false`，不启动 Rerank 服务也可以正常运行项目。如需启用重排功能，请先部署兼容 `/rerank` 接口的 Python 服务，再将配置改为：

```yaml
ai:
  rerank:
    enabled: true
    base-url: http://localhost:8000
```

---

## 🚀 快速开始

### 1. 克隆项目

```bash
git clone <repository-url>
cd ai-assistant
```

### 2. 启动依赖服务

#### 2.1 MySQL 数据库

确保 MySQL 服务已启动，然后执行初始化脚本创建数据库和表：

```bash
mysql -u root -p < ai-assistant-server/src/main/resources/db/schema.sql
```

> 默认数据库名：`ai_assistant`，字符集：`utf8mb4`

#### 2.2 Ollama 本地向量模型

> ⚠️ **必须在应用启动前完成，否则后端会启动失败**

1. **安装 Ollama**：访问 [https://ollama.com](https://ollama.com) 下载安装

2. **拉取 bge-m3 嵌入模型**：

```bash
ollama pull bge-m3
```

> bge-m3 为多语言模型，中文效果优秀，向量维度 1024

3. **验证 Ollama 服务**：

```bash
# 确保服务运行在 http://localhost:11434
curl http://localhost:11434/api/tags
```

#### 2.3 Rerank 重排服务（可选）

Rerank 功能需要独立部署的 Python 重排服务，基于 `bge-reranker-v2-m3` 模型：

```bash
# 示例：使用 FlagEmbedding 启动重排服务
pip install FlagEmbedding fastapi uvicorn
# 编写一个简单的 FastAPI 服务，监听 8000 端口提供 /rerank 接口
```

> 如果不部署重排服务，将 `ai.rerank.enabled` 设为 `false` 即可，RAG 流水线会自动跳过重排阶段。

### 3. 配置说明

后端配置文件位于 `ai-assistant-server/src/main/resources/application.yaml`，主要配置项如下：

#### 3.1 服务端口

```yaml
server:
  port: 8900    # 后端服务端口
```

#### 3.2 数据库连接

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/ai_assistant
    username: root
    password: 123456    # 请修改为你的 MySQL 密码
```

#### 3.3 DeepSeek API 配置

```yaml
spring:
  ai:
    deepseek:
      api-key: sk-xxx    # 替换为你的 DeepSeek API Key
      chat:
        options:
          model: deepseek-v4-flash
          temperature: 0.3
      base-url: https://api.deepseek.com
```

> 🔑 **API Key 获取**：前往 [DeepSeek 开放平台](https://platform.deepseek.com) 注册并获取 API Key

#### 3.4 Ollama Embedding 配置

```yaml
spring:
  ai:
    ollama:
      base-url: http://localhost:11434
      chat:
        enabled: false     # 聊天使用 DeepSeek，关闭 Ollama chat
      embedding:
        options:
          model: bge-m3    # 嵌入模型名称
```

#### 3.5 文件上传配置

```yaml
spring:
  servlet:
    multipart:
      max-file-size: 500MB     # 单个文件最大 500MB
      max-request-size: 500MB  # 单次请求最大 500MB
```

#### 3.6 向量检索配置

```yaml
ai:
  embedding:
    batch-size: 16          # 批量向量化 chunk 数（根据显存调整）
  vector-store:
    log-sample-vector: false          # 是否打印向量示例（调试用）
    persistence-path: ./vector-store  # 向量数据持久化目录
  vector-search:
    top-k-per-query: 4      # 每个查询的 TopK
    final-top-n: 6          # 合并后的最终 TopN
    min-score: 0.5          # 最低相似度阈值
    metadata-filter: true   # 是否启用元数据过滤
```

> 向量数据默认持久化到 `./vector-store` 目录，重启后自动加载；删除该目录会清空所有向量化的文档数据。

#### 3.7 Rerank 重排配置

```yaml
ai:
  rerank:
    enabled: false                # 是否启用重排
    model: bge-reranker-v2-m3     # 重排模型名称
    base-url: http://localhost:8000  # 重排服务地址
    top-m: 4                      # 重排后保留数量
    min-score: 0.3                # 重排最低分数
    batch-size: 8                 # 批处理大小
    timeout: 5000                 # 超时时间（毫秒）
    fusion:
      enabled: false              # 是否启用向量得分融合
      alpha: 0.7                  # 融合权重（rerank 得分占比）
```

#### 3.8 RAG 上下文配置

```yaml
ai:
  rag:
    context:
      max-chunks: 5               # 最大上下文 chunk 数
      max-tokens: 3000            # 最大 Token 数
      format: numbered            # 输出格式：numbered / markdown / plain
```

#### 3.9 Groundedness 事实校验配置

```yaml
ai:
  rag:
    groundedness:
      enabled: true               # 是否启用事实校验
      threshold: 0.7              # 整体支撑度阈值
      mark-unsupported: true      # 标注未支撑语句（⚠️）
      fail-open: true             # 校验失败时是否放行（true=放行）
      timeout: 10000              # 超时时间（毫秒）
```

#### 3.10 前端代理配置

前端开发环境通过 Vite 代理转发 API 请求，配置文件 `ai-assistant-frontend/vite.config.ts`：

```typescript
server: {
  port: 5173,
  proxy: {
    '/api': {
      target: env.VITE_PROXY_TARGET || 'http://localhost:8900',
      changeOrigin: true,
      rewrite: (p) => p.replace(/^\/api/, ''),
    },
  },
}
```

> 可通过环境变量 `VITE_PROXY_TARGET` 覆盖默认后端地址

### 4. 启动后端

```bash
# 方式一：使用 Maven Wrapper（推荐）
./mvnw -pl ai-assistant-server -am spring-boot:run

# 方式二：使用本地 Maven
mvn -pl ai-assistant-server -am spring-boot:run

# 方式三：先打包再运行
./mvnw -pl ai-assistant-server -am clean package -DskipTests
java -jar ai-assistant-server/target/ai-assistant-server-0.0.1-SNAPSHOT.jar
```

启动成功后访问：
- **API 服务**：http://localhost:8900
- **Swagger 文档**：http://localhost:8900/swagger-ui.html

### 5. 启动前端

```bash
cd ai-assistant-frontend

# 安装依赖
npm install

# 启动开发服务器
npm run dev
```

启动成功后访问：http://localhost:5173

---

## 📡 API 概览

### 聊天与会话

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/chat/session/create` | 创建会话 |
| GET | `/chat/session/list` | 获取会话列表 |
| GET | `/chat/session/{sessionId}` | 获取会话详情 |
| POST | `/chat/session/rename` | 重命名会话 |
| POST | `/chat/session/delete` | 删除会话（逻辑删除） |
| POST | `/chat/send` | 发送消息（同步） |
| GET | `/chat/stream` | 发送消息（SSE 流式） |
| GET | `/chat/messages` | 获取消息列表 |

### 文档

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/document/upload` | 上传单个文件（异步处理） |
| POST | `/document/batchUpLoad` | 批量上传文件 |

### 用户

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/sysUser/addUser` | 新增用户 |
| GET | `/sysUser/{id}` | 获取用户 |
| GET | `/sysUser/list` | 用户列表 |
| POST | `/sysUser/update` | 更新用户 |
| POST | `/sysUser/delete` | 删除用户 |

> 完整 API 文档请启动后端后访问 Swagger UI：http://localhost:8900/swagger-ui.html

### SSE 流式协议

聊天流式接口通过 Server-Sent Events 传输，事件协议如下：

```
event: start
data: {"sessionId": "...", "messageId": "..."}

event: message
data: "流式文本片段..."

event: done
data: {"content": "完整回答", "citations": [...]}

event: error
data: {"code": 500, "message": "..."}
```

---

## 🧩 RAG 流水线

完整的 RAG 处理流程包含以下阶段，每个阶段独立降级，单点故障不会中断整个对话：

```
用户输入
   │
   ▼
┌──────────────┐   简单问题：直接透传
│ Query Router │   上下文问题：结合历史改写
└──────┬───────┘   复杂问题：多查询扩展
       │
       ▼
┌──────────────┐   多路向量召回 → 合并去重 → TopN 筛选
│ 向量检索      │   支持 min-score 过滤 + 元数据过滤
└──────┬───────┘
       │
       ▼
┌──────────────┐   bge-reranker-v2-m3 交叉编码器精排
│ Rerank 重排   │   可选向量得分融合
└──────┬───────┘
       │
       ▼
┌──────────────┐   按 Token 预算 / chunk 数 / 分数截断
│ 上下文组装    │   三种输出格式：numbered / markdown / plain
└──────┬───────┘
       │
       ▼
┌──────────────┐   System + 上下文 + 历史 + 用户问题
│ Prompt 构建   │   RAG / Fallback 两套 System Prompt
└──────┬───────┘
       │
       ▼
┌──────────────┐   DeepSeek 流式生成
│  大模型生成   │   SSE 逐字输出
└──────┬───────┘
       │
       ▼
┌──────────────┐   LLM-as-judge 校验回答是否被原文支撑
│ 事实校验      │   未支撑语句标注 ⚠️，低支撑度加警告
└──────┬───────┘
       │
       ▼
  返回回答 + 引用
```

---

## 📦 构建与部署

### 前端构建

```bash
cd ai-assistant-frontend
npm run build
```

构建产物输出到 `ai-assistant-frontend/dist/` 目录，可部署到 Nginx 等静态服务器。

### 后端构建

```bash
./mvnw -pl ai-assistant-server -am clean package -DskipTests
```

构建产物：`ai-assistant-server/target/ai-assistant-server-0.0.1-SNAPSHOT.jar`

### 生产部署示例

```bash
# 运行 JAR 包，指定环境变量覆盖配置
java -jar ai-assistant-server/target/ai-assistant-server-0.0.1-SNAPSHOT.jar \
  --spring.datasource.password=your_password \
  --spring.ai.deepseek.api-key=your_api_key \
  --ai.vector-store.persistence-path=/data/vector-store
```

---

## ❓ 常见问题

### Q1: 应用启动失败，提示 Ollama 连接错误？

确保已安装 Ollama 并拉取了 bge-m3 模型：

```bash
ollama list    # 查看已安装模型，确认 bge-m3 在列表中
ollama pull bge-m3
```

### Q2: DeepSeek API 调用失败？

1. 检查 `api-key` 是否正确
2. 确认账户有足够的额度
3. 检查网络是否能访问 `api.deepseek.com`

### Q3: 大文件上传失败？

修改 `application.yaml` 中的文件大小限制：

```yaml
spring:
  servlet:
    multipart:
      max-file-size: 1000MB
      max-request-size: 1000MB
```

### Q4: 向量数据存储在哪里？

默认存储在项目根目录的 `vector-store/` 下，由 `ai.vector-store.persistence-path` 配置。删除该目录会清空所有向量化的文档数据。

### Q5: Rerank 服务未启动会怎样？

如果 `ai.rerank.enabled=true` 但服务不可用，RAG 流水线会捕获异常并自动降级——跳过重排，直接使用向量检索结果。所有 RAG 阶段均有独立的异常降级机制。

### Q6: 如何关闭 Groundedness 事实校验？

将 `ai.rag.groundedness.enabled` 设为 `false` 即可关闭。关闭后回答不会进行事实支撑性校验，也不会显示 ⚠️ 标注。

---

## 📚 详细文档

项目 `docs/` 目录下包含各模块的详细设计与系统分析文档：

| 文档 | 说明 |
|------|------|
| [聊天会话接口设计文档](docs/聊天会话接口设计文档.md) | 聊天会话 API 设计详解 |
| [前端聊天接口对接文档](docs/前端聊天接口对接文档.md) | 前端接口对接指南 |
| [Query 路由处理设计文档](docs/Query路由处理设计文档.md) | 查询路由设计 |
| [向量检索与 TopK 召回设计文档](docs/向量检索与TopK召回设计文档.md) | 向量检索设计 |
| [Rerank 重排系统分析文档](docs/Rerank重排系统分析文档.md) | 重排模块分析 |
| [RAG 上下文组装与 Prompt 功能设计文档](docs/RAG上下文组装与Prompt功能设计文档.md) | 上下文组装与 Prompt 设计 |
| [Groundedness 事实校验系统分析](docs/rag-groundedness-system-analysis.md) | 事实校验模块分析 |
| [文件上传到向量持久化系统分析文档](docs/文件上传到向量持久化系统分析文档.md) | 文档处理全流程分析 |
| [知识库与文档 Chunk 表设计文档](docs/知识库与文档Chunk表设计文档.md) | 数据库表设计 |
| [项目结构详解](PROJECT_STRUCTURE.md) | 完整项目结构说明 |

---

## 🔗 技术栈参考

- [Spring Boot 官方文档](https://spring.io/projects/spring-boot)
- [Spring AI 官方文档](https://spring.io/projects/spring-ai)
- [DeepSeek API 文档](https://platform.deepseek.com/docs)
- [Ollama 官方文档](https://ollama.com/library)
- [bge-reranker (FlagEmbedding)](https://github.com/FlagOpen/FlagEmbedding)
- [MyBatis-Plus 官方文档](https://baomidou.com/)
- [Vite 官方文档](https://vitejs.dev/)
- [React 官方文档](https://react.dev/)
