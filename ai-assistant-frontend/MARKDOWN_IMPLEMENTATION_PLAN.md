# 前端 Markdown 流式渲染实现方案

## 一、现状分析

### 后端 SSE 协议
- 接口：`GET /chat/stream?sessionId=&userId=&content=`
- 格式：标准 SSE + 命名事件 JSON（`start` / `message` / `done` / `error`）
- message 事件：`{ event: "message", content: "<增量文本>", index }`
- done 事件：`{ event: "done", fullContent: "<完整文本>", messageId, finishReason }`

### 前端当前问题
1. **协议不匹配**：`useSSE` / `fetchSSE` 用 POST 请求、不解析 `event:` 字段、直接拼接 `data:` 内容
2. **路径不匹配**：Vite proxy 前缀 `/api` 未 strip，后端路径是 `/chat/stream` 而非 `/api/chat/stream`
3. **无 Markdown 渲染**：消息以纯文本形式渲染在 `<span>` 中
4. **ChatDemo 直接用 useSSE**：未走 `api/chat.ts` 的 streamChat 封装

---

## 二、技术选型

### Markdown 渲染：`react-markdown` + `remark-gfm` + `rehype-highlight`
- **react-markdown**：React 生态最常用、最安全的 markdown 渲染器（默认不允许原始 HTML，防 XSS）
- **remark-gfm**：支持 GitHub Flavored Markdown（表格、删除线、任务列表、脚注）
- **rehype-highlight** + **highlight.js**：代码块语法高亮
- 流式友好：每次内容变化时重新渲染整段 markdown，React 会 diff 更新 DOM

### 代码高亮主题
- 使用 `highlight.js` 的 GitHub 主题（适配明暗模式）

---

## 三、实现步骤

### Step 1：安装依赖
```bash
npm install react-markdown remark-gfm rehype-highlight
npm install -D @types/highlight.js
```
（`rehype-highlight` 依赖 `highlight.js`，会自动安装）

### Step 2：修复 SSE 工具层（`src/utils/sse.ts`）
- 新增 `event:` 字段解析，将事件名与 data 一起回调
- 保留原 `onMessage` 兼容旧用法，新增 `onEvent(eventName, data)` 回调
- 或更直接：将 `data` 统一解析为 `{ event, payload }` 结构

### Step 3：重写 `useSSE` hook（`src/hooks/useSSE.ts`）
- 改为解析后端的命名事件格式
- 状态扩展：`{ data, loading, error, fullContent, messageId, onStart/onMessage/onDone/onError }`
- 支持 GET 请求 + URLSearchParams 参数
- 保持中止（AbortController）能力

### Step 4：新增 Markdown 组件（`src/components/Markdown.tsx`）
- 封装 `react-markdown` 配置（remark-gfm、rehype-highlight）
- 统一的 className 结构，便于样式定制
- 流式渲染时自动处理不完整 markdown（react-markdown 天然容错）

### Step 5：新增 Markdown 样式（`src/components/Markdown.css`）
- 完整的 markdown 内容样式：标题、段落、列表、引用、表格、代码块、行内代码、链接、图片等
- 适配暗色模式（`prefers-color-scheme: dark`）
- 代码块高亮样式（基于 highlight.js GitHub 主题调整）

### Step 6：重写 ChatDemo 组件（`src/components/ChatDemo.tsx`）
- 用户消息保持纯文本气泡
- AI 消息使用 `<Markdown>` 组件渲染
- 流式过程中打字光标效果调整（放在 markdown 末尾）
- 对接正确的后端接口：GET `/chat/stream` + query params
- 临时处理：硬编码 `sessionId` 和 `userId`（当前后端无鉴权）

### Step 7：修复 Vite 代理配置
- 给 `/api` 代理加 `rewrite: (path) => path.replace(/^\/api/, '')` 以去掉 `/api` 前缀
- 或调整 `.env.development` 中的 base URL

### Step 8：更新 API 层（`src/api/chat.ts`）
- 新增流式聊天的类型定义（匹配后端事件结构）
- 新增 `streamChat()` 函数，使用更新后的 SSE 工具
- 旧的非流式接口保留

### Step 9：类型定义补充（`src/types/chat.ts` 或就地）
- `ChatStreamEvent` 类型
- 会话、消息等类型

---

## 四、文件变更清单

**新增文件：**
- `src/components/Markdown.tsx` — Markdown 渲染组件
- `src/components/Markdown.css` — Markdown 样式
- `src/types/chat.ts` — 聊天相关 TypeScript 类型

**修改文件：**
- `src/utils/sse.ts` — 支持命名事件解析、GET 请求
- `src/hooks/useSSE.ts` — 适配后端 SSE 事件协议
- `src/api/chat.ts` — 重写 streamChat，补充类型
- `src/components/ChatDemo.tsx` — 接入 Markdown 组件 + 正确的流式接口
- `vite.config.ts` — 修复代理前缀
- `.env.development` — 调整后端端口为 8900

---

## 五、流式渲染策略

**增量渲染方案**：每次收到新 token，将累积的完整文本传给 `react-markdown` 重新渲染。
- 优点：实现简单，react-markdown 自动处理不完整语法（如半开的代码块、未闭合的 `**`）
- 性能：消息长度在几千字以内时完全够用，React 的 diff 保证只更新变化的 DOM
- 光标：流式过程中在末尾追加 `▍` 字符作为打字光标，done 后移除

**代码块流式优化（可选）**：
- 检测到不完整的代码围栏（奇数个 ` ``` `）时，补一个关闭围栏以保证高亮正确
- 可在后续优化中加入

---

## 六、安全考量

- `react-markdown` 默认禁用原始 HTML（`skipHtml: true` 是默认行为），防止 XSS
- 链接自动加 `target="_blank"` 和 `rel="noopener noreferrer"`
- 不启用 `dangerouslySetInnerHTML` 模式

---

## 七、验证方式

1. `npm install` 安装新依赖
2. `npm run dev` 启动前端
3. 确保后端在 8900 端口运行
4. 输入消息，验证：
   - 流式打字机效果正常
   - Markdown 语法正确渲染（标题、列表、代码块、加粗等）
   - 代码块有语法高亮
   - 暗色模式下样式正确
   - 停止按钮可中止流式输出
