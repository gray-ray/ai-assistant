# 项目结构说明

> 项目名称：**ai-assistant**
> 后端已拆分为 Maven 多模块工程；前端 `ai-assistant-frontend` 仍保持独立 Vite 项目。

---

## 1. 顶层结构

```text
ai-assistant/
├── pom.xml                         # Maven 父工程，packaging=pom
├── ai-assistant-common/            # 通用响应、异常、Spring 配置、MyBatis 填充器
├── ai-assistant-rag-api/           # RAG 对外契约、请求/响应模型
├── ai-assistant-rag-core/          # RAG 实现、Prompt、向量存储、模型适配
├── ai-assistant-user/              # 用户模块
├── ai-assistant-document/          # 文档上传、解析、清洗、分块
├── ai-assistant-chat/              # 聊天会话、消息、SSE、RAG 编排
├── ai-assistant-server/            # 启动类、应用配置、SQL、可运行 jar
├── ai-assistant-frontend/          # React + Vite 前端项目
├── docs/                           # 设计文档
├── upload/                         # 上传文件运行时目录
└── vector-store/                   # SimpleVectorStore JSON 持久化目录
```

---

## 2. 后端模块

| 模块 | 职责 | 主要内容 |
|------|------|----------|
| `ai-assistant-common` | 通用基础能力 | `common/result`、`common/exception`、`common/config`、`common/handler` |
| `ai-assistant-rag-api` | RAG 稳定契约 | RAG 模型、路由请求、检索/重排/上下文/Prompt/Groundedness 接口 |
| `ai-assistant-rag-core` | RAG 具体实现 | Query Router 实现、查询改写/扩展、向量检索实现、Rerank 实现、Prompt 实现、Groundedness 实现、向量存储实现、`prompts/` 资源 |
| `ai-assistant-user` | 用户域 | 用户 controller、dto、entity、mapper、service、`mapper/user` XML |
| `ai-assistant-document` | 文档域 | 文档上传、PDF 解析、文本清洗、分块、`mapper/document` XML |
| `ai-assistant-chat` | 聊天域 | 会话/消息 controller、dto、entity、mapper、service、vo、SSE |
| `ai-assistant-server` | 启动与聚合 | `AiAssistantApplication`、`application.yaml`、`db/*.sql`、集成测试、Spring Boot repackage |

---

## 3. 依赖方向

```text
ai-assistant-server
├── ai-assistant-common
├── ai-assistant-rag-core
├── ai-assistant-user
├── ai-assistant-document
└── ai-assistant-chat

ai-assistant-rag-core -> ai-assistant-rag-api -> ai-assistant-common
ai-assistant-user     -> ai-assistant-common
ai-assistant-document -> ai-assistant-common + ai-assistant-rag-api
ai-assistant-chat     -> ai-assistant-common + ai-assistant-rag-api
```

约束：

- `ai-assistant-rag-core` 不依赖 `chat`、`document`、`user`。
- `ai-assistant-chat` 和 `ai-assistant-document` 只依赖 `ai-assistant-rag-api`，不依赖 RAG 实现模块。
- 只有 `ai-assistant-server` 负责最终 Spring Boot 可运行 jar 打包。

---

## 4. 资源归属

| 资源 | 位置 |
|------|------|
| 应用配置 | `ai-assistant-server/src/main/resources/application.yaml` |
| 数据库脚本 | `ai-assistant-server/src/main/resources/db/*.sql` |
| 用户 Mapper XML | `ai-assistant-user/src/main/resources/mapper/user/*.xml` |
| 文档 Mapper XML | `ai-assistant-document/src/main/resources/mapper/document/*.xml` |
| RAG Prompt 模板 | `ai-assistant-rag-core/src/main/resources/prompts/**/*.st` |

`application.yaml` 使用 `classpath*:mapper/**/*.xml` 扫描多个模块 jar 中的 MyBatis XML。

---

## 5. 常用命令

Windows 当前环境需要显式设置 JDK 21：

```powershell
$env:JAVA_HOME='C:\Users\28393\.jdks\ms-21.0.12'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
$env:MAVEN_OPTS='-Duser.home=C:\Users\28393'
```

```bash
# 校验 reactor
./mvnw validate

# 运行全部测试
./mvnw test

# 只构建后端可运行 jar
./mvnw -pl ai-assistant-server -am package

# 运行后端
java -jar ai-assistant-server/target/ai-assistant-server-0.0.1-SNAPSHOT.jar
```
