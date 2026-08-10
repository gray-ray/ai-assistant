/**
 * useSSE - React Hook 封装，适配后端命名事件 SSE 协议
 *
 * 后端事件序列：start → N 个 message → done / error
 *
 * 注意：onDone/onError 回调中可以直接访问到 fullContent/aiMessageId 最新值
 *       （通过 ref 解决闭包问题）
 */
import { useCallback, useRef, useState } from 'react'
import { fetchSSE, createSSEController, type SSEOptions } from '../utils/sse'
import type { ChatStreamEvent, CitationVO } from '../types/chat'

interface UseStreamChatOptions extends Partial<SSEOptions> {
  /** 初始累积文本 */
  initial?: string
}

export interface UseStreamChatReturn {
  /** 累积的 AI 回复文本（流式过程中持续更新） */
  data: string
  /** 是否在连接/接收中 */
  loading: boolean
  /** 错误信息 */
  error: string | null
  /** AI 回复的完整内容（done 后由 fullContent 填充，用于最终校验） */
  fullContent: string
  /** AI 消息 ID（done 后获得） */
  aiMessageId: number | null
  /** 用户消息 ID（start 后获得） */
  userMessageId: number | null
  /** 用户消息序号（start 后获得） */
  userMessageIndex: number | null
  /** 引用来源列表（done 事件携带） */
  citations: CitationVO[]
  /** 发起流式聊天请求 */
  start: (overrides?: Partial<SSEOptions>) => Promise<void>
  /** 中止连接 */
  stop: () => void
  /** 重置状态 */
  reset: () => void
}

export function useSSE(options: UseStreamChatOptions = {}): UseStreamChatReturn {
  const { initial = '', ...baseOptions } = options

  const [data, setData] = useState<string>(initial)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [fullContent, setFullContent] = useState<string>('')
  const [aiMessageId, setAiMessageId] = useState<number | null>(null)
  const [userMessageId, setUserMessageId] = useState<number | null>(null)
  const [userMessageIndex, setUserMessageIndex] = useState<number | null>(null)
  const [citations, setCitations] = useState<CitationVO[]>([])

  // 使用 ref 存储最新值，解决回调闭包问题
  const fullContentRef = useRef('')
  const aiMessageIdRef = useRef<number | null>(null)
  const citationsRef = useRef<CitationVO[]>([])
  const controllerRef = useRef<ReturnType<typeof createSSEController> | null>(null)

  const stop = useCallback(() => {
    if (controllerRef.current) {
      controllerRef.current.abort()
      controllerRef.current = null
    }
    setLoading(false)
  }, [])

  const reset = useCallback(() => {
    stop()
    setData(initial)
    setFullContent('')
    setError(null)
    setAiMessageId(null)
    setUserMessageId(null)
    setUserMessageIndex(null)
    setCitations([])
    fullContentRef.current = ''
    aiMessageIdRef.current = null
    citationsRef.current = []
  }, [initial, stop])

  const handleEvent = useCallback((eventName: string, rawData: string) => {
    try {
      const evt = JSON.parse(rawData) as ChatStreamEvent
      switch (evt.event) {
        case 'start':
          setData('')
          setFullContent('')
          setAiMessageId(null)
          setCitations([])
          fullContentRef.current = ''
          aiMessageIdRef.current = null
          citationsRef.current = []
          if (evt.messageId) setUserMessageId(evt.messageId)
          if (evt.index) setUserMessageIndex(evt.index)
          break
        case 'message':
          if (evt.content) {
            setData((prev) => prev + evt.content!)
          }
          break
        case 'done':
          if (evt.fullContent) {
            setData(evt.fullContent)
            setFullContent(evt.fullContent)
            fullContentRef.current = evt.fullContent
          }
          if (evt.messageId) {
            setAiMessageId(evt.messageId)
            aiMessageIdRef.current = evt.messageId
          }
          if (evt.citations && evt.citations.length > 0) {
            setCitations(evt.citations)
            citationsRef.current = evt.citations
          }
          break
        case 'error':
          setError(evt.message || '未知错误')
          break
      }
    } catch {
      if (eventName === 'message' && rawData && rawData !== '[DONE]') {
        setData((prev) => prev + rawData)
      }
    }
  }, [])

  const start = useCallback(
    async (overrides: Partial<SSEOptions> = {}) => {
      if (controllerRef.current) {
        controllerRef.current.abort()
      }

      const controller = createSSEController()
      controllerRef.current = controller
      setLoading(true)
      setError(null)
      setFullContent('')
      setAiMessageId(null)
      setUserMessageId(null)
      setUserMessageIndex(null)
      setCitations([])
      fullContentRef.current = ''
      aiMessageIdRef.current = null
      citationsRef.current = []
      setData('')

      // 创建一个包装对象，让回调能通过 ref 获取最新值
      const merged: SSEOptions = {
        url: baseOptions.url || '',
        method: baseOptions.method || 'GET',
        params: baseOptions.params,
        body: baseOptions.body,
        headers: baseOptions.headers,
        baseURL: baseOptions.baseURL,
        signal: controller.signal,
        onEvent: (eventName, evtData) => {
          handleEvent(eventName, evtData)
          baseOptions.onEvent?.(eventName, evtData)
          overrides.onEvent?.(eventName, evtData)
        },
        onMessage: (chunk) => {
          baseOptions.onMessage?.(chunk)
          overrides.onMessage?.(chunk)
        },
        onDone: () => {
          setLoading(false)
          // 调用外部 onDone 时传入最新的 fullContent 和 aiMessageId
          baseOptions.onDone?.()
          overrides.onDone?.()
        },
        onError: (err) => {
          setError(err.message)
          setLoading(false)
          baseOptions.onError?.(err)
          overrides.onError?.(err)
        },
        ...overrides,
      }

      await fetchSSE(merged)
    },
    [baseOptions, handleEvent],
  )

  return {
    data,
    loading,
    error,
    fullContent,
    aiMessageId,
    userMessageId,
    userMessageIndex,
    citations,
    start,
    stop,
    reset,
  }
}
