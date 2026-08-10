# 消息聊天功能实现计划

## 一、项目现状分析

### 技术栈
- **框架**: Spring Boot 3.4.4 + Java 21
- **ORM**: MyBatis-Plus 3.5.7
- **AI**: Spring AI 1.1.2 + DeepSeek（`spring-ai-starter-model-deepseek`）
- **文档**: SpringDoc OpenAPI (Swagger)
- **工具**: Lombok、Validation

### 已有资源
1. **DeepSeek 已配置** — `application.yaml` 中 `spring.ai.deepseek` 已配置 api-key、model、base-url
2. **数据库表已建好** — `chat_session`（会话表）和 `chat_message`（消息表）在 `schema.sql` 中已定义
3. **统一响应结构** — `Result<T>` + `ResultCode` 枚举
4. **异常处理** — `BusinessException` + `GlobalExceptionHandler`
5. **自动填充** — `MyMetaObjectHandler`（createTime、updateTime）
6. **代码模式** — Entity / Mapper / Service(Impl) / Controller 四层结构，MyBatis-Plus IService/ServiceImpl 模式

### chat_session 表结构
| 字段 | 类型 | 说明 |
|------|------|------|
| id | bigint PK auto | 主键 |
| user_id | bigint | 用户id |
| session_id | varchar(64) UK | 业务会话id |
| title | varchar(255) | 会话标题 |
| session_type | varchar(50) | 会话类型 |
| model_name | varchar(50) | 模型名称 |
| summary | text | 上下文摘要 |
| create_time / update_time | datetime | 自动填充 |
| is_deleted | tinyint | 逻辑删除 |

### chat_message 表结构
| 字段 | 类型 | 说明 |
|------|------|------|
| id | bigint PK auto | 主键 |
| session_id | bigint | 会话id（外键关联 chat_session.id） |
| user_id | bigint | 用户id |
| role | varchar(20) | user/assistant/system |
| content | longtext | 消息内容 |
| message_index | int | 消息顺序 |
| model_name | varchar(50) | 模型名称 |
| finish_reason | varchar(50) | 结束原因 |
| create_time | datetime | 自动填充 |
| is_deleted | tinyint | 逻辑删除 |

---

## 二、功能范围

### 核心功能
1. **会话管理** — 创建会话、查询会话列表、查看会话详情、修改会话标题、删除会话
2. **消息聊天** — 发送消息（同步）、流式发送消息（SSE）、获取消息列表
3. **上下文管理** — 加载历史消息作为上下文传给 DeepSeek

### 暂不包含（后续迭代）
- 多轮对话摘要压缩
- 流式返回的 token 用量统计
- 消息点赞/重试
- 会话分享/导出

---

## 三、API 设计

### 会话接口
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/chat/session/create` | 创建新会话 |
| GET | `/chat/session/list?userId=` | 获取用户会话列表 |
| GET | `/chat/session/{sessionId}` | 获取会话详情 |
| POST | `/chat/session/rename` | 修改会话标题 |
| POST | `/chat/session/delete` | 删除会话（逻辑删除） |

### 消息接口
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/chat/send` | 发送消息（同步返回完整回复） |
| GET | `/chat/messages?sessionId=` | 获取某会话下的消息列表 |

---

## 四、实现步骤

### Step 1: 创建实体类 (Entity)
- `ChatSession.java` — 对应 `chat_session` 表
- `ChatMessage.java` — 对应 `chat_message` 表
- 使用 MyBatis-Plus 注解：`@TableName`、`@TableId(type = IdType.AUTO)`、`@TableLogic`、`@TableField(fill = FieldFill.INSERT)` 等

### Step 2: 创建 Mapper 接口
- `ChatSessionMapper.java` — 继承 `BaseMapper<ChatSession>`
- `ChatMessageMapper.java` — 继承 `BaseMapper<ChatMessage>`

### Step 3: 创建 DTO 类
- `ChatSessionCreateDTO` — 创建会话请求（userId、title、sessionType）
- `ChatSessionRenameDTO` — 重命名请求（sessionId、title）
- `ChatSendRequestDTO` — 发送消息请求（sessionId、userId、content）
- `ChatSendResponseDTO` — 同步发送响应（messageId、content、...）
- `ChatMessageVO` — 消息视图对象

### Step 4: 创建 Service 层
- `ChatSessionService` 接口 + `ChatSessionServiceImpl` 实现
  - 创建会话（自动生成 sessionId，UUID）
  - 会话列表、详情、重命名、删除
- `ChatService` 接口 + `ChatServiceImpl` 实现
  - 发送消息（同步）：加载历史消息 → 组装 prompt → 调用 DeepSeek ChatModel → 保存用户消息和AI回复 → 返回
  - 消息列表查询

### Step 5: 创建 Controller
- `ChatController` — 提供会话和消息的 REST API
- 统一使用 `Result<T>` 响应

### Step 6: DeepSeek 集成说明
- 直接注入 Spring AI 的 `ChatModel`（starter 已自动装配 deepseek 的实现）
- 使用 `ChatModel.call()` 同步调用
- 组装 `List<Message>`：system + 历史对话 + 当前用户消息
- 历史消息从 `chat_message` 表按 `message_index` 升序加载

### 关键设计细节

**会话标识**：使用业务 `sessionId`（UUID 字符串）作为对外暴露的标识，`id` 是数据库自增主键，作为 `chat_message.session_id` 外键关联。

**消息顺序**：`message_index` 每条消息递增，确保对话顺序正确。

**上下文加载**：发送新消息时，从数据库加载该会话所有历史消息（后续可优化为限制条数/支持摘要）。

**模型名称**：从配置或请求参数获取，存入 `chat_session.model_name` 和 `chat_message.model_name`。

---

## 五、文件清单（新增）

```
src/main/java/org/grayray/aiassistant/
├── entity/
│   ├── ChatSession.java
│   └── ChatMessage.java
├── mapper/
│   ├── ChatSessionMapper.java
│   └── ChatMessageMapper.java
├── dto/
│   ├── ChatSessionCreateDTO.java
│   ├── ChatSessionRenameDTO.java
│   ├── ChatSendRequestDTO.java
│   └── ChatMessageVO.java
├── service/
│   ├── ChatSessionService.java
│   ├── ChatService.java
│   └── impl/
│       ├── ChatSessionServiceImpl.java
│       └── ChatServiceImpl.java
└── controller/
    └── ChatController.java
```

---

## 六、验证方式
1. 启动应用，访问 Swagger UI: `http://localhost:8900/swagger-ui.html`
2. 测试创建会话接口
3. 测试发送消息接口，确认 DeepSeek 返回正常回复
4. 测试消息列表接口，确认消息已持久化
5. 测试会话列表、重命名、删除接口
