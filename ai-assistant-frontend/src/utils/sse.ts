/**
 * SSE (Server-Sent Events) 流式请求封装
 * 支持标准 SSE 协议（含 `event:` 命名事件字段），兼容 fetch + ReadableStream
 *
 * 后端协议：每个 SSE 事件格式为
 *   event: <name>
 *   data: <json>
 *   <空行>
 * 事件类型：start / message / done / error
 */

export interface SSEOptions {
  /** 请求地址（绝对或相对于 baseURL） */
  url: string
  /** HTTP 方法，默认 GET */
  method?: 'GET' | 'POST'
  /** 请求体（POST 时使用） */
  body?: unknown
  /** 查询参数（GET 时使用，会自动 URL 编码） */
  params?: Record<string, string | number | boolean | undefined>
  /** 额外请求头 */
  headers?: Record<string, string>
  /** baseURL，默认使用环境变量 */
  baseURL?: string
  /**
   * 每个 SSE 事件触发，携带事件名和解析后的 data 字符串
   * 默认事件名为 'message'（即没有 event: 字段时）
   */
  onEvent?: (eventName: string, data: string) => void
  /** 接收到一条消息时触发（兼容旧用法，等价于 onEvent('message', data)） */
  onMessage?: (data: string) => void
  /** 流结束时触发 */
  onDone?: () => void
  /** 发生错误时触发 */
  onError?: (error: Error) => void
  /** 中止信号 */
  signal?: AbortSignal
}

/**
 * 使用 fetch + ReadableStream 处理 SSE 流式响应
 */
export async function fetchSSE(options: SSEOptions): Promise<void> {
  const {
    url,
    method = 'GET',
    body,
    params,
    headers = {},
    baseURL = import.meta.env.VITE_SSE_BASE_URL || '/api',
    onEvent,
    onMessage,
    onDone,
    onError,
    signal,
  } = options

  // 构建完整 URL（拼接 query params）
  let fullUrl = url.startsWith('http') ? url : `${baseURL}${url}`
  if (method === 'GET' && params) {
    const usp = new URLSearchParams()
    for (const [key, val] of Object.entries(params)) {
      if (val !== undefined && val !== null && val !== '') {
        usp.append(key, String(val))
      }
    }
    const qs = usp.toString()
    if (qs) {
      fullUrl += (fullUrl.includes('?') ? '&' : '?') + qs
    }
  }

  // 组装请求头
  const token = localStorage.getItem('token')
  const requestHeaders: Record<string, string> = {
    Accept: 'text/event-stream',
    ...headers,
  }
  if (method === 'POST' && body) {
    requestHeaders['Content-Type'] = 'application/json'
  }
  if (token) {
    requestHeaders.Authorization = `Bearer ${token}`
  }

  try {
    const response = await fetch(fullUrl, {
      method,
      headers: requestHeaders,
      body: method === 'POST' && body ? JSON.stringify(body) : undefined,
      signal,
    })

    if (!response.ok) {
      throw new Error(`HTTP error! status: ${response.status}`)
    }

    if (!response.body) {
      throw new Error('ReadableStream not supported in this browser')
    }

    const reader = response.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buffer = ''

    let currentEvent = 'message'
    let currentData = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) break

      buffer += decoder.decode(value, { stream: true })

      // 按行处理
      const lines = buffer.split('\n')
      // 保留最后一个可能不完整的片段
      buffer = lines.pop() || ''

      for (const line of lines) {
        const trimmed = line.trimEnd()

        // 空行：事件分隔
        if (trimmed === '') {
          if (currentData) {
            onEvent?.(currentEvent, currentData)
            if (currentEvent === 'message') {
              onMessage?.(currentData)
            }
            currentData = ''
          }
          currentEvent = 'message'
          continue
        }

        // 注释行，忽略
        if (trimmed.startsWith(':')) {
          continue
        }

        // event: <name>
        if (trimmed.startsWith('event:')) {
          currentEvent = trimmed.slice(6).trimStart()
          continue
        }

        // data: <value>
        if (trimmed.startsWith('data:')) {
          const dataStr = trimmed.slice(5).trimStart()
          if (currentData) {
            currentData += '\n' + dataStr
          } else {
            currentData = dataStr
          }
          continue
        }

        // id: / retry: 等其他字段暂时忽略
      }
    }

    // 流结束时如果还有未发送的 data
    if (currentData) {
      onEvent?.(currentEvent, currentData)
      if (currentEvent === 'message') {
        onMessage?.(currentData)
      }
    }

    onDone?.()
  } catch (error) {
    if ((error as Error).name === 'AbortError') {
      onDone?.()
      return
    }
    const err = error instanceof Error ? error : new Error(String(error))
    console.error('[SSE Error]', err)
    onError?.(err)
  }
}

/**
 * 创建一个可取消的 SSE 请求控制器
 */
export function createSSEController() {
  const controller = new AbortController()
  return {
    signal: controller.signal,
    abort: () => controller.abort(),
  }
}
