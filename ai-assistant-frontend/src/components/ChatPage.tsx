/**
 * 聊天页面主组件
 * 整合会话列表侧边栏 + 聊天窗口
 */
import SessionSidebar from './SessionSidebar'
import ChatWindow from './ChatWindow'
import { useChatSession } from '../hooks/useChatSession'
import './ChatPage.css'

export default function ChatPage() {
  const {
    sessions,
    currentSession,
    currentSessionId,
    messages,
    loading,
    streaming,
    sessionsLoading,
    selectSession,
    createNewSession,
    renameSession,
    deleteSession,
    sendMessage,
    stopGeneration,
  } = useChatSession()

  const handleCreateSession = async () => {
    await createNewSession()
  }

  const handleSelectSession = async (sessionId: string) => {
    await selectSession(sessionId)
  }

  return (
    <div className="chat-page">
      <SessionSidebar
        sessions={sessions}
        currentSessionId={currentSessionId}
        sessionsLoading={sessionsLoading}
        onSelectSession={handleSelectSession}
        onCreateSession={handleCreateSession}
        onRenameSession={renameSession}
        onDeleteSession={deleteSession}
      />

      {currentSessionId ? (
        <ChatWindow
          title={currentSession?.title || '新会话'}
          messages={messages}
          streaming={streaming}
          loading={loading}
          onSend={sendMessage}
          onStop={stopGeneration}
        />
      ) : (
        <div className="chat-window chat-empty-state">
          <div className="empty-state-content">
            <div className="empty-icon">💬</div>
            <h3>开始新的对话</h3>
            <p>创建一个新会话，开始与 AI 助手交流</p>
            <button className="create-btn" onClick={handleCreateSession}>
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <path d="M12 5v14M5 12h14" />
              </svg>
              新建会话
            </button>
          </div>
        </div>
      )}
    </div>
  )
}
