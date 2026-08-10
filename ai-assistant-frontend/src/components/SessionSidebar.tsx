/**
 * 会话列表侧边栏
 */
import { useState } from 'react'
import type { ChatSession } from '../types/chat'
import { formatSessionTime, truncateText } from '../utils/format'
import './SessionSidebar.css'

interface SessionSidebarProps {
  sessions: ChatSession[]
  currentSessionId: string | null
  sessionsLoading: boolean
  onSelectSession: (sessionId: string) => void
  onCreateSession: () => void
  onRenameSession: (sessionId: string, title: string) => void
  onDeleteSession: (sessionId: string) => void
}

export default function SessionSidebar({
  sessions,
  currentSessionId,
  sessionsLoading,
  onSelectSession,
  onCreateSession,
  onRenameSession,
  onDeleteSession,
}: SessionSidebarProps) {
  const [editingId, setEditingId] = useState<string | null>(null)
  const [editTitle, setEditTitle] = useState('')
  const [deleteConfirmId, setDeleteConfirmId] = useState<string | null>(null)

  const handleStartRename = (session: ChatSession, e: React.MouseEvent) => {
    e.stopPropagation()
    setEditingId(session.sessionId)
    setEditTitle(session.title)
    setDeleteConfirmId(null)
  }

  const handleFinishRename = (sessionId: string) => {
    const title = editTitle.trim()
    if (title && title !== sessions.find((s) => s.sessionId === sessionId)?.title) {
      onRenameSession(sessionId, title)
    }
    setEditingId(null)
    setEditTitle('')
  }

  const handleCancelRename = () => {
    setEditingId(null)
    setEditTitle('')
  }

  const handleDeleteClick = (sessionId: string, e: React.MouseEvent) => {
    e.stopPropagation()
    setDeleteConfirmId(deleteConfirmId === sessionId ? null : sessionId)
    setEditingId(null)
  }

  const handleConfirmDelete = (sessionId: string, e: React.MouseEvent) => {
    e.stopPropagation()
    onDeleteSession(sessionId)
    setDeleteConfirmId(null)
  }

  return (
    <aside className="session-sidebar">
      <div className="sidebar-header">
        <h1 className="sidebar-logo">AI 助手</h1>
        <button className="new-session-btn" onClick={onCreateSession} title="新建会话">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <path d="M12 5v14M5 12h14" />
          </svg>
          新建会话
        </button>
      </div>

      <div className="session-list">
        {sessionsLoading && sessions.length === 0 && (
          <div className="session-empty">加载中…</div>
        )}

        {!sessionsLoading && sessions.length === 0 && (
          <div className="session-empty">
            <p>暂无会话</p>
            <p className="session-empty-hint">点击"新建会话"开始对话</p>
          </div>
        )}

        {sessions.map((session) => {
          const isActive = session.sessionId === currentSessionId
          const isEditing = editingId === session.sessionId
          const showDeleteConfirm = deleteConfirmId === session.sessionId

          return (
            <div
              key={session.sessionId}
              className={`session-item ${isActive ? 'active' : ''}`}
              onClick={() => {
                if (!isEditing) onSelectSession(session.sessionId)
              }}
            >
              {isEditing ? (
                <div className="session-edit-form" onClick={(e) => e.stopPropagation()}>
                  <input
                    type="text"
                    value={editTitle}
                    onChange={(e) => setEditTitle(e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter') handleFinishRename(session.sessionId)
                      if (e.key === 'Escape') handleCancelRename()
                    }}
                    autoFocus
                    maxLength={50}
                  />
                  <div className="session-edit-actions">
                    <button onClick={() => handleFinishRename(session.sessionId)} className="btn-confirm">
                      确定
                    </button>
                    <button onClick={handleCancelRename} className="btn-cancel">
                      取消
                    </button>
                  </div>
                </div>
              ) : showDeleteConfirm ? (
                <div className="session-delete-confirm" onClick={(e) => e.stopPropagation()}>
                  <span>确定删除？</span>
                  <div className="session-edit-actions">
                    <button
                      onClick={(e) => handleConfirmDelete(session.sessionId, e)}
                      className="btn-danger"
                    >
                      删除
                    </button>
                    <button onClick={() => setDeleteConfirmId(null)} className="btn-cancel">
                      取消
                    </button>
                  </div>
                </div>
              ) : (
                <>
                  <div className="session-info">
                    <div className="session-title" title={session.title}>
                      <svg className="session-icon" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                        <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
                      </svg>
                      {truncateText(session.title, 18)}
                    </div>
                    <div className="session-meta">
                      <span>{formatSessionTime(session.updateTime)}</span>
                    </div>
                  </div>
                  <div className="session-actions">
                    <button
                      className="session-action-btn"
                      onClick={(e) => handleStartRename(session, e)}
                      title="重命名"
                    >
                      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                        <path d="M17 3a2.828 2.828 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5L17 3z" />
                      </svg>
                    </button>
                    <button
                      className="session-action-btn"
                      onClick={(e) => handleDeleteClick(session.sessionId, e)}
                      title="删除"
                    >
                      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                        <polyline points="3 6 5 6 21 6" />
                        <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2" />
                      </svg>
                    </button>
                  </div>
                </>
              )}
            </div>
          )
        })}
      </div>
    </aside>
  )
}
