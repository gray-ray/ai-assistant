/**
 * 引用来源列表组件
 *
 * 显示 assistant 消息引用的文档片段列表，点击可展开查看完整片段内容。
 */
import { useState } from 'react'
import type { CitationVO } from '../types/chat'
import './CitationList.css'

interface CitationListProps {
  citations: CitationVO[]
  /** 被高亮的引用编号（用于正文 [n] 悬浮联动） */
  activeIndex?: number | null
}

export default function CitationList({ citations, activeIndex = null }: CitationListProps) {
  const [expanded, setExpanded] = useState<Record<number, boolean>>({})

  if (!citations || citations.length === 0) return null

  const toggle = (index: number) => {
    setExpanded((prev) => ({ ...prev, [index]: !prev[index] }))
  }

  return (
    <div className="citation-list">
      <div className="citation-list-header">
        <span className="citation-list-icon">📚</span>
        <span className="citation-list-title">引用来源</span>
        <span className="citation-list-count">{citations.length}</span>
      </div>
      <ul className="citation-list-body">
        {citations.map((c) => (
          <li
            key={c.index}
            data-citation-index={c.index}
            className={`citation-item ${activeIndex === c.index ? 'is-active' : ''}`}
          >
            <button
              type="button"
              className="citation-item-header"
              onClick={() => toggle(c.index)}
              aria-expanded={!!expanded[c.index]}
            >
              <span className="citation-index">[{c.index}]</span>
              <span className="citation-source">
                <span className="citation-doc">{c.documentName}</span>
                {c.chapterTitle && (
                  <span className="citation-chapter"> · {c.chapterTitle}</span>
                )}
              </span>
              <span className={`citation-toggle ${expanded[c.index] ? 'is-open' : ''}`}>
                ▾
              </span>
            </button>
            {expanded[c.index] && (
              <div className="citation-item-content">
                <pre>{c.content}</pre>
                {c.score !== undefined && (
                  <div className="citation-score">
                    相关度：{(c.score * 100).toFixed(0)}%
                  </div>
                )}
              </div>
            )}
          </li>
        ))}
      </ul>
    </div>
  )
}
