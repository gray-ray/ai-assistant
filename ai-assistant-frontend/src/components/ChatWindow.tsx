/**
 * 聊天窗口组件（消息列表 + 输入框）
 */
import MessageList from './MessageList'
import MessageInput from './MessageInput'
import type { DisplayMessage } from '../hooks/useChatSession'
import './ChatWindow.css'

interface ChatWindowProps {
  title: string
  messages: DisplayMessage[]
  streaming: boolean
  loading: boolean
  onSend: (content: string) => void
  onStop: () => void
}

export default function ChatWindow({
  title,
  messages,
  streaming,
  loading,
  onSend,
  onStop,
}: ChatWindowProps) {
  return (
    <div className="chat-window">
      <div className="chat-header">
        <h2 className="chat-title">{title || '新会话'}</h2>
        {streaming && (
          <span className="streaming-badge">
            <span className="pulse-dot" />
            AI 正在思考
          </span>
        )}
      </div>

      <MessageList messages={messages} streaming={streaming} loading={loading} />

      <MessageInput
        onSend={onSend}
        onStop={onStop}
        streaming={streaming}
        disabled={streaming}
      />
    </div>
  )
}
