import { useState } from 'react'
import ChatPage from './components/ChatPage'
import KnowledgePage from './components/KnowledgePage'
import type { KnowledgeBase } from './types/knowledge'
import './App.css'

type AppView = 'chat' | 'knowledge'

interface KnowledgeChatRequest {
  requestId: number
  knowledgeId: number
  knowledgeName: string
}

function App() {
  const [activeView, setActiveView] = useState<AppView>('chat')
  const [knowledgeChatRequest, setKnowledgeChatRequest] = useState<KnowledgeChatRequest | null>(null)

  const handleStartKnowledgeChat = (knowledge: KnowledgeBase) => {
    setKnowledgeChatRequest({
      requestId: Date.now(),
      knowledgeId: knowledge.id,
      knowledgeName: knowledge.name,
    })
    setActiveView('chat')
  }

  return (
    <div className="app-container">
      <nav className="app-rail" aria-label="主导航">
        <button
          className={`app-rail-btn ${activeView === 'chat' ? 'active' : ''}`}
          onClick={() => setActiveView('chat')}
          title="聊天"
          type="button"
        >
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
          </svg>
          <span>聊天</span>
        </button>
        <button
          className={`app-rail-btn ${activeView === 'knowledge' ? 'active' : ''}`}
          onClick={() => setActiveView('knowledge')}
          title="知识库"
          type="button"
        >
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20" />
            <path d="M4 4.5A2.5 2.5 0 0 1 6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15z" />
          </svg>
          <span>知识库</span>
        </button>
      </nav>

      <div className="app-view">
        {activeView === 'chat' ? (
          <ChatPage startKnowledgeChatRequest={knowledgeChatRequest} />
        ) : (
          <KnowledgePage onStartChat={handleStartKnowledgeChat} />
        )}
      </div>
    </div>
  )
}

export default App
