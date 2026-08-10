/**
 * 消息列表组件
 */
import { useEffect, useRef } from 'react'
import type { DisplayMessage } from '../hooks/useChatSession'
import { formatTime } from '../utils/format'
import Markdown from './Markdown'
import CitationList from './CitationList'
import './MessageList.css'

interface MessageListProps {
  messages: DisplayMessage[]
  streaming: boolean
  loading: boolean
  emptyHint?: string
}

export default function MessageList({
  messages,
  streaming,
  loading,
  emptyHint = '开始对话吧~ 试试让我写一段代码或解释一个概念',
}: MessageListProps) {
  const listRef = useRef<HTMLDivElement>(null)
  const bottomRef = useRef<HTMLDivElement>(null)

  // 自动滚动到底部
  useEffect(() => {
    if (bottomRef.current) {
      bottomRef.current.scrollIntoView({ behavior: streaming ? 'auto' : 'smooth' })
    }
  }, [messages, streaming])

  return (
    <div className="message-list" ref={listRef}>
      {loading && (
        <div className="message-loading">
          <div className="loading-dots">
            <span />
            <span />
            <span />
          </div>
          <span>加载历史消息…</span>
        </div>
      )}

      {!loading && messages.length === 0 && (
        <div className="message-welcome">
          <div className="welcome-icon">🤖</div>
          <h2>你好，我是 AI 助手</h2>
          <p>{emptyHint}</p>
        </div>
      )}

      {messages.map((msg, idx) => {
        const isUser = msg.role === 'user'
        const isStreaming = msg.content === '' && streaming && idx === messages.length - 1
        const isLast = idx === messages.length - 1
        const showCursor = isLast && !isUser && streaming && !msg.isError

        return (
          <div
            key={idx}
            className={`message-item ${isUser ? 'message-user' : 'message-assistant'} ${msg.isError ? 'message-error' : ''}`}
          >
            {!isUser && (
              <div className="message-avatar avatar-ai">
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                  <rect x="3" y="11" width="18" height="10" rx="2" />
                  <circle cx="12" cy="5" r="2" />
                  <path d="M12 7v4M8 16h.01M16 16h.01" />
                </svg>
              </div>
            )}

            <div className="message-body">
              <div className={`message-bubble ${isStreaming ? 'thinking' : ''}`}>
                {msg.isError ? (
                  <div className="error-content">
                    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                      <circle cx="12" cy="12" r="10" />
                      <line x1="12" y1="8" x2="12" y2="12" />
                      <line x1="12" y1="16" x2="12.01" y2="16" />
                    </svg>
                    <span>{msg.content}</span>
                  </div>
                ) : msg.content ? (
                  isUser ? (
                    <div className="user-text">{msg.content}</div>
                  ) : (
                    <Markdown cursor={showCursor}>{msg.content}</Markdown>
                  )
                ) : (
                  <div className="thinking-indicator">
                    <span />
                    <span />
                    <span />
                  </div>
                )}
                {!isUser && msg.citations && msg.citations.length > 0 && (
                  <CitationList citations={msg.citations} />
                )}
              </div>
              {msg.createTime && (
                <div className="message-time">{formatTime(msg.createTime)}</div>
              )}
            </div>

            {isUser && (
              <div className="message-avatar avatar-user">
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                  <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" />
                  <circle cx="12" cy="7" r="4" />
                </svg>
              </div>
            )}
          </div>
        )
      })}

      <div ref={bottomRef} />
    </div>
  )
}
