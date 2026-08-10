/**
 * 对话相关 API
 */
import request from '../utils/request'
import { fetchSSE, type SSEOptions } from '../utils/sse'
import type {
  ChatMessageVO,
  ChatSession,
  CreateSessionRequest,
  DeleteSessionRequest,
  RenameSessionRequest,
  SendMessageRequest,
} from '../types/chat'

export type { ChatMessage } from '../types/chat'

// ========== 会话管理 ==========

/** 创建会话 */
export function createSession(data: CreateSessionRequest) {
  return request.post<ChatSession>('/chat/session/create', data)
}

/** 获取会话列表 */
export function getSessionList(userId: number) {
  return request.get<ChatSession[]>(`/chat/session/list?userId=${userId}`)
}

/** 获取会话详情 */
export function getSessionDetail(sessionId: string) {
  return request.get<ChatSession>(`/chat/session/${sessionId}`)
}

/** 重命名会话 */
export function renameSession(data: RenameSessionRequest) {
  return request.post('/chat/session/rename', data)
}

/** 删除会话 */
export function deleteSession(data: DeleteSessionRequest) {
  return request.post('/chat/session/delete', data)
}

// ========== 消息 ==========

/** 获取会话历史消息 */
export function getChatMessages(sessionId: string, userId: number) {
  return request.get<ChatMessageVO[]>(`/chat/messages?sessionId=${sessionId}&userId=${userId}`)
}

/** 发送消息（同步） */
export function sendMessage(data: SendMessageRequest) {
  return request.post<ChatMessageVO>('/chat/send', data)
}

// ========== SSE 流式 ==========

export interface StreamChatOptions
  extends Omit<SSEOptions, 'url' | 'method' | 'params' | 'onEvent'> {
  sessionId: string
  userId: number
  content: string
  systemPrompt?: string
  /** 每个 SSE 事件回调 */
  onEvent?: (eventName: string, data: string) => void
}

/**
 * 流式对话（SSE）- 使用 GET + query params
 * 事件：start → message* → done / error
 */
export function streamChat(options: StreamChatOptions): Promise<void> {
  const { sessionId, userId, content, systemPrompt, onEvent, ...rest } = options

  return fetchSSE({
    url: '/chat/stream',
    method: 'GET',
    params: {
      sessionId,
      userId,
      content,
      systemPrompt,
    },
    onEvent,
    ...rest,
  })
}
