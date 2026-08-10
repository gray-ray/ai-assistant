/**
 * useChatSession - 聊天会话管理 Hook
 *
 * 管理：
 * - 会话列表（加载、创建、重命名、删除）
 * - 当前会话及历史消息
 * - 消息发送（SSE 流式）
 * - 消息状态（加载中、流式中、错误）
 */
import { useCallback, useEffect, useRef, useState } from 'react'
import {
  createSession as apiCreateSession,
  deleteSession as apiDeleteSession,
  getChatMessages,
  getSessionList,
  renameSession as apiRenameSession,
} from '../api/chat'
import type { ChatMessageVO, ChatSession, CitationVO } from '../types/chat'
import { useSSE } from './useSSE'

// 开发阶段使用固定 userId，后续接入登录后替换
const DEFAULT_USER_ID = 1
// 自动重命名的最大标题长度（从首条消息截取）
const AUTO_TITLE_MAX_LEN = 20

// 前端展示用消息结构
export interface DisplayMessage {
  messageId?: number
  role: 'user' | 'assistant' | 'system'
  content: string
  modelName?: string | null
  finishReason?: 'stop' | 'length' | null
  createTime?: string
  /** 是否为错误消息 */
  isError?: boolean
  /** 引用来源列表（仅 assistant 消息有值） */
  citations?: CitationVO[]
}

export interface UseChatSessionOptions {
  userId?: number
  systemPrompt?: string
}

export interface UseChatSessionReturn {
  userId: number
  sessions: ChatSession[]
  currentSession: ChatSession | null
  currentSessionId: string | null
  messages: DisplayMessage[]
  /** 加载历史消息中 */
  loading: boolean
  /** SSE 流式输出中 */
  streaming: boolean
  /** 当前正在生成的流式内容 */
  streamContent: string
  /** SSE 错误信息 */
  streamError: string | null
  /** 加载会话列表中 */
  sessionsLoading: boolean

  // 会话操作
  selectSession: (sessionId: string) => Promise<void>
  createNewSession: (title?: string) => Promise<ChatSession | null>
  renameSession: (sessionId: string, title: string) => Promise<boolean>
  deleteSession: (sessionId: string) => Promise<boolean>
  refreshSessions: () => Promise<void>

  // 消息操作
  sendMessage: (content: string) => Promise<void>
  stopGeneration: () => void

  inputDisabled: boolean
}

export function useChatSession(options: UseChatSessionOptions = {}): UseChatSessionReturn {
  const { userId = DEFAULT_USER_ID, systemPrompt } = options

  // 会话状态
  const [sessions, setSessions] = useState<ChatSession[]>([])
  const [currentSessionId, setCurrentSessionId] = useState<string | null>(null)
  const [messages, setMessages] = useState<DisplayMessage[]>([])
  const [sessionsLoading, setSessionsLoading] = useState(false)
  const [loading, setLoading] = useState(false)

  // 追踪流式状态（用于 useEffect 监听完成）
  const isFirstMessageRef = useRef(false)
  const wasStreamingRef = useRef(false)
  const sendingTextRef = useRef('')

  const {
    data: streamContent,
    loading: streaming,
    error: streamError,
    fullContent,
    aiMessageId,
    citations: streamCitations,
    start: startStream,
    stop: stopStream,
    reset: resetStream,
  } = useSSE({ url: '/chat/stream', method: 'GET' })

  const currentSession = sessions.find((s) => s.sessionId === currentSessionId) || null

  // ========== 会话列表 ==========

  const refreshSessions = useCallback(async () => {
    setSessionsLoading(true)
    try {
      const list = await getSessionList(userId)
      list.sort((a, b) => new Date(b.updateTime).getTime() - new Date(a.updateTime).getTime())
      setSessions(list)
    } catch (err) {
      console.error('获取会话列表失败:', err)
      setSessions([])
    } finally {
      setSessionsLoading(false)
    }
  }, [userId])

  // 初始加载会话列表
  useEffect(() => {
    refreshSessions()
  }, [refreshSessions])

  // ========== 切换会话 ==========

  const selectSession = useCallback(
    async (sessionId: string) => {
      if (sessionId === currentSessionId) return
      stopStream()
      resetStream()
      wasStreamingRef.current = false

      setCurrentSessionId(sessionId)
      setLoading(true)
      isFirstMessageRef.current = false

      try {
        const history = await getChatMessages(sessionId, userId)
        history.sort((a, b) => a.messageIndex - b.messageIndex)
        const displayMsgs: DisplayMessage[] = history.map((m: ChatMessageVO) => ({
          messageId: m.messageId,
          role: m.role,
          content: m.content,
          modelName: m.modelName,
          finishReason: m.finishReason,
          createTime: m.createTime,
          citations: m.citations && m.citations.length > 0 ? m.citations : undefined,
        }))
        setMessages(displayMsgs)
        isFirstMessageRef.current = history.length === 0
      } catch (err) {
        console.error('加载历史消息失败:', err)
        setMessages([])
      } finally {
        setLoading(false)
      }
    },
    [currentSessionId, userId, stopStream, resetStream],
  )

  // ========== 创建会话 ==========

  const createNewSession = useCallback(
    async (title?: string): Promise<ChatSession | null> => {
      stopStream()
      resetStream()
      wasStreamingRef.current = false

      try {
        const session = await apiCreateSession({
          userId,
          title: title || '新会话',
          sessionType: 'normal',
        })
        setSessions((prev) => [session, ...prev])
        setCurrentSessionId(session.sessionId)
        setMessages([])
        isFirstMessageRef.current = true
        setLoading(false)
        return session
      } catch (err) {
        console.error('创建会话失败:', err)
        return null
      }
    },
    [userId, stopStream, resetStream],
  )

  // ========== 重命名会话 ==========

  const renameSession = useCallback(
    async (sessionId: string, title: string): Promise<boolean> => {
      try {
        await apiRenameSession({ sessionId, title })
        setSessions((prev) => prev.map((s) => (s.sessionId === sessionId ? { ...s, title } : s)))
        return true
      } catch (err) {
        console.error('重命名会话失败:', err)
        return false
      }
    },
    [],
  )

  // ========== 删除会话 ==========

  const deleteSession = useCallback(
    async (sessionId: string): Promise<boolean> => {
      try {
        await apiDeleteSession({ sessionId })

        if (sessionId === currentSessionId) {
          stopStream()
          resetStream()
          wasStreamingRef.current = false
          setMessages([])

          const remaining = sessions.filter((s) => s.sessionId !== sessionId)
          if (remaining.length > 0) {
            const nextId = remaining[0].sessionId
            setCurrentSessionId(nextId)
            // 异步加载下一个会话
            setTimeout(() => selectSession(nextId), 0)
          } else {
            setCurrentSessionId(null)
          }
        }

        setSessions((prev) => prev.filter((s) => s.sessionId !== sessionId))
        return true
      } catch (err) {
        console.error('删除会话失败:', err)
        return false
      }
    },
    [currentSessionId, sessions, stopStream, resetStream, selectSession],
  )

  // ========== 监听流式完成/错误，收尾消息 ==========

  useEffect(() => {
    // 从 streaming=true -> streaming=false 表示流式结束
    if (wasStreamingRef.current && !streaming) {
      wasStreamingRef.current = false

      if (streamError) {
        // 流式出错
        setMessages((prev) => [
          ...prev,
          {
            role: 'assistant',
            content: streamError.includes('AI') ? streamError : 'AI 回复失败，请重试',
            isError: true,
          },
        ])
      } else {
        // 正常完成
        const finalContent = fullContent || streamContent
        if (finalContent) {
          setMessages((prev) => [
            ...prev,
            {
              role: 'assistant',
              content: finalContent,
              messageId: aiMessageId ?? undefined,
              finishReason: 'stop',
              citations:
                streamCitations && streamCitations.length > 0 ? streamCitations : undefined,
            },
          ])
        }
      }

      resetStream()

      // 刷新会话列表
      refreshSessions()

      // 首次发消息自动重命名
      if (isFirstMessageRef.current && sendingTextRef.current && currentSessionId) {
        isFirstMessageRef.current = false
        const text = sendingTextRef.current
        const autoTitle =
          text.slice(0, AUTO_TITLE_MAX_LEN) + (text.length > AUTO_TITLE_MAX_LEN ? '…' : '')
        // 不 await，异步执行
        apiRenameSession({ sessionId: currentSessionId, title: autoTitle })
          .then(() => {
            setSessions((prev) =>
              prev.map((s) =>
                s.sessionId === currentSessionId ? { ...s, title: autoTitle } : s,
              ),
            )
          })
          .catch(() => {})
      }
      sendingTextRef.current = ''
    }
  }, [streaming, streamError, fullContent, streamContent, aiMessageId, streamCitations, currentSessionId, resetStream, refreshSessions])

  // ========== 发送消息（SSE 流式） ==========

  const sendMessage = useCallback(
    async (content: string) => {
      const text = content.trim()
      if (!text || streaming) return

      // 如果没有当前会话，自动创建
      let sid = currentSessionId
      if (!sid) {
        const newSession = await createNewSession()
        if (!newSession) return
        sid = newSession.sessionId
      }

      // 记录发送文本用于自动重命名
      sendingTextRef.current = text

      // 乐观添加用户消息
      setMessages((prev) => [...prev, { role: 'user', content: text }])

      wasStreamingRef.current = true

      await startStream({
        params: {
          sessionId: sid,
          userId,
          content: text,
          ...(systemPrompt ? { systemPrompt } : {}),
        },
      })
    },
    [streaming, currentSessionId, userId, systemPrompt, createNewSession, startStream],
  )

  // ========== 停止生成 ==========

  const stopGeneration = useCallback(() => {
    if (!streaming) return
    stopStream()
    // stopStream 会触发 streaming -> false 的 useEffect，
    // 但因为是主动停止，不走错误/正常完成的逻辑
    // 手动将当前内容固化
    wasStreamingRef.current = false
    if (streamContent) {
      setMessages((prev) => [
        ...prev,
        {
          role: 'assistant',
          content: streamContent,
          finishReason: 'stop',
        },
      ])
    }
    resetStream()
    refreshSessions()
    sendingTextRef.current = ''
    isFirstMessageRef.current = false
  }, [streaming, stopStream, streamContent, resetStream, refreshSessions])

  // ========== 构造展示消息列表 ==========

  const displayMessages: DisplayMessage[] = (() => {
    if (!streaming) return messages
    // 流式中追加一条 assistant 临时消息显示实时内容
    return [
      ...messages,
      {
        role: 'assistant',
        content: streamContent || '',
      },
    ]
  })()

  return {
    userId,
    sessions,
    currentSession,
    currentSessionId,
    messages: displayMessages,
    loading,
    streaming,
    streamContent,
    streamError,
    sessionsLoading,
    selectSession,
    createNewSession,
    renameSession,
    deleteSession,
    refreshSessions,
    sendMessage,
    stopGeneration,
    inputDisabled: streaming,
  }
}
