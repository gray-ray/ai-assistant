/**
 * 聊天相关 TypeScript 类型定义
 * 与后端 `doc/前端聊天接口对接文档.md` 保持一致
 */

// ========== 通用 ==========

export interface ApiResult<T> {
  code: number
  message: string
  data: T
}

// ========== 会话 ==========

export interface ChatSession {
  id: number
  sessionId: string
  userId: number
  title: string
  sessionType: string
  modelName: string
  createTime: string
  updateTime: string
}

// ========== 引用 ==========

export interface CitationVO {
  /** 引用编号（对应回答中的 [n]） */
  index: number
  /** 文档ID */
  documentId: number
  /** 文档名称 */
  documentName: string
  /** 章节标题 */
  chapterTitle?: string
  /** 片段内容（完整，用于悬浮展示） */
  content: string
  /** 相关性分数 */
  score?: number
}

// ========== 消息 ==========

export type ChatRole = 'user' | 'assistant' | 'system'

export interface ChatMessageVO {
  messageId: number
  sessionId: string
  role: ChatRole
  content: string
  modelName: string | null
  finishReason: 'stop' | 'length' | null
  messageIndex: number
  createTime: string
  /** 引用来源列表（RAG 检索命中的文档片段，仅 assistant 消息有值） */
  citations?: CitationVO[]
}

// 前端消息展示用（简化版）
export interface ChatMessage {
  role: ChatRole
  content: string
  messageId?: number
  modelName?: string | null
  finishReason?: 'stop' | 'length' | null
}

// ========== SSE 流式事件 ==========

export type ChatStreamEventType = 'start' | 'message' | 'done' | 'error'

export interface ChatStreamEvent {
  event: ChatStreamEventType
  sessionId?: string
  messageId?: number
  index?: number
  content?: string
  fullContent?: string
  finishReason?: 'stop' | 'length'
  modelName?: string
  /** 引用来源列表（done 事件携带） */
  citations?: CitationVO[]
  code?: number
  message?: string
}

// ========== 请求 DTO ==========

export interface CreateSessionRequest {
  userId: number
  title?: string
  sessionType?: string
}

export interface SendMessageRequest {
  sessionId: string
  userId: number
  content: string
  systemPrompt?: string
}

export interface RenameSessionRequest {
  sessionId: string
  title: string
}

export interface DeleteSessionRequest {
  sessionId: string
}
