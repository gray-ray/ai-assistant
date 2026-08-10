/**
 * 消息输入框组件
 */
import { useRef, useState } from 'react'
import './MessageInput.css'

interface MessageInputProps {
  onSend: (content: string) => void
  onStop: () => void
  disabled?: boolean
  streaming?: boolean
  placeholder?: string
}

const MAX_LENGTH = 10000

export default function MessageInput({
  onSend,
  onStop,
  disabled = false,
  streaming = false,
  placeholder = '输入消息，Enter 发送，Shift+Enter 换行…',
}: MessageInputProps) {
  const [value, setValue] = useState('')
  const textareaRef = useRef<HTMLTextAreaElement>(null)

  const handleSend = () => {
    const text = value.trim()
    if (!text || disabled) return
    if (text.length > MAX_LENGTH) {
      alert(`消息长度不能超过 ${MAX_LENGTH} 字`)
      return
    }
    onSend(text)
    setValue('')
    // 重置 textarea 高度
    if (textareaRef.current) {
      textareaRef.current.style.height = 'auto'
    }
  }

  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault()
      handleSend()
    }
  }

  const handleChange = (e: React.ChangeEvent<HTMLTextAreaElement>) => {
    setValue(e.target.value)
    // 自动调整高度
    const el = e.target
    el.style.height = 'auto'
    el.style.height = Math.min(el.scrollHeight, 160) + 'px'
  }

  const charCount = value.length
  const isNearLimit = charCount > MAX_LENGTH * 0.9
  const isOverLimit = charCount > MAX_LENGTH

  return (
    <div className="message-input-container">
      <div className={`message-input-box ${streaming ? 'streaming' : ''}`}>
        <textarea
          ref={textareaRef}
          value={value}
          onChange={handleChange}
          onKeyDown={handleKeyDown}
          placeholder={streaming ? 'AI 正在回复中…' : placeholder}
          disabled={disabled && !streaming}
          rows={1}
          className="message-textarea"
        />
        <div className="input-actions">
          {charCount > 0 && (
            <span className={`char-count ${isNearLimit ? 'warn' : ''} ${isOverLimit ? 'error' : ''}`}>
              {charCount}/{MAX_LENGTH}
            </span>
          )}
          {streaming ? (
            <button className="send-btn stop-btn" onClick={onStop} title="停止生成">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor">
                <rect x="6" y="6" width="12" height="12" rx="2" />
              </svg>
              <span>停止</span>
            </button>
          ) : (
            <button
              className="send-btn"
              onClick={handleSend}
              disabled={disabled || !value.trim() || isOverLimit}
              title="发送消息 (Enter)"
            >
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <line x1="22" y1="2" x2="11" y2="13" />
                <polygon points="22 2 15 22 11 13 2 9 22 2" />
              </svg>
            </button>
          )}
        </div>
      </div>
      <div className="input-footer">
        <span>AI 生成内容仅供参考，请核实重要信息</span>
      </div>
    </div>
  )
}
