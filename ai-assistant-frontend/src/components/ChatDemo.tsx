/**
 * 对话示例组件
 * - 支持 Markdown 流式渲染
 * - 对接后端 SSE 命名事件协议（start / message / done / error）
 */
import { useMemo, useState } from 'react'
import { useSSE } from '../hooks/useSSE'
import type { ChatMessage } from '../api/chat'
import Markdown from './Markdown'

// 临时测试用的 userId 和 sessionId（后续接入登录/会话管理后替换）
const TEST_USER_ID = 2
const TEST_SESSION_ID = 'demo-session-001'

export default function ChatDemo() {
  const [input, setInput] = useState('')
  const [messages, setMessages] = useState<ChatMessage[]>([])

  const {
    data: streamedText,
    loading,
    error,
    start,
    stop,
    reset,
    fullContent,
  } = useSSE({
    url: '/chat/stream',
    method: 'GET',
    initial: '',
  })

  const handleSend = async () => {
    const text = input.trim()
    if (!text || loading) return

    const userMsg: ChatMessage = { role: 'user', content: text }
    setMessages((prev) => [...prev, userMsg])
    setInput('')
    reset()

    // 发起流式请求
    await start({
      params: {
        sessionId: TEST_SESSION_ID,
        userId: TEST_USER_ID,
        content: text,
      },
      onDone: () => {
        // done 事件后 fullContent 已由 hook 更新到最终值
        const finalContent = fullContent || streamedText
        if (finalContent) {
          setMessages((prev) => [...prev, { role: 'assistant', content: finalContent }])
        }
        reset()
      },
      onError: () => {
        // 错误处理已在 hook 中设置 error state
        reset()
      },
    })
  }

  // 正在流式输出时的临时消息列表
  const displayMessages = useMemo(() => {
    if (!loading) return messages
    return [...messages, { role: 'assistant' as const, content: streamedText }]
  }, [loading, messages, streamedText])

  const isStreaming = loading && streamedText.length > 0

  return (
    <div
      style={{
        maxWidth: 820,
        margin: '0 auto',
        padding: 24,
        display: 'flex',
        flexDirection: 'column',
        minHeight: '80vh',
      }}
    >
      <h2>AI 助手（流式输出 + Markdown）</h2>

      <div
        style={{
          flex: 1,
          overflowY: 'auto',
          border: '1px solid var(--border)',
          borderRadius: 8,
          padding: 16,
          marginBottom: 12,
          background: 'var(--bg)',
        }}
      >
        {displayMessages.length === 0 && (
          <p style={{ color: '#999' }}>开始对话吧~ 试试让我写一段代码或解释一个概念</p>
        )}

        {displayMessages.map((msg, idx) => {
          const isLast = idx === displayMessages.length - 1
          const showCursor = isLast && msg.role === 'assistant' && isStreaming

          return (
            <div
              key={idx}
              style={{
                marginBottom: 16,
                textAlign: msg.role === 'user' ? 'right' : 'left',
              }}
            >
              {msg.role === 'user' ? (
                <span
                  style={{
                    display: 'inline-block',
                    padding: '8px 14px',
                    borderRadius: 12,
                    background: '#4f8cff',
                    color: '#fff',
                    maxWidth: '70%',
                    whiteSpace: 'pre-wrap',
                    wordBreak: 'break-word',
                    textAlign: 'left',
                  }}
                >
                  {msg.content}
                </span>
              ) : (
                <div
                  style={{
                    display: 'inline-block',
                    padding: '10px 16px',
                    borderRadius: 12,
                    background: 'var(--code-bg)',
                    maxWidth: '80%',
                    minWidth: '40px',
                  }}
                >
                  {msg.content ? (
                    <Markdown cursor={showCursor}>{msg.content}</Markdown>
                  ) : (
                    <span style={{ color: '#999', fontStyle: 'italic' }}>思考中…</span>
                  )}
                </div>
              )}
            </div>
          )
        })}
      </div>

      {error && <p style={{ color: '#ff6b6b', marginBottom: 8 }}>错误: {error}</p>}

      <div style={{ display: 'flex', gap: 8 }}>
        <input
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && !e.shiftKey && handleSend()}
          placeholder="输入消息，Enter 发送…"
          style={{
            flex: 1,
            padding: '10px 14px',
            borderRadius: 8,
            border: '1px solid var(--border)',
            fontSize: 14,
            background: 'var(--bg)',
            color: 'var(--text)',
          }}
          disabled={loading}
        />
        {loading ? (
          <button
            onClick={stop}
            style={{
              padding: '10px 18px',
              borderRadius: 8,
              border: 'none',
              background: '#ff6b6b',
              color: '#fff',
              cursor: 'pointer',
            }}
          >
            停止
          </button>
        ) : (
          <button
            onClick={handleSend}
            style={{
              padding: '10px 18px',
              borderRadius: 8,
              border: 'none',
              background: '#4f8cff',
              color: '#fff',
              cursor: 'pointer',
            }}
          >
            发送
          </button>
        )}
      </div>
    </div>
  )
}
