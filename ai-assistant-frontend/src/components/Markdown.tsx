/**
 * Markdown 渲染组件
 * 基于 react-markdown + remark-gfm + rehype-highlight
 * 支持流式渲染（不完整 markdown 会优雅降级）
 * 支持自动将正文中的 [n] 渲染为可点击的引用角标
 */
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import rehypeHighlight from 'rehype-highlight'
import { renderCitationLinks } from './CitationRef'
import './Markdown.css'
import './CitationList.css'

interface MarkdownProps {
  /** markdown 文本内容 */
  children: string
  /** 是否显示打字光标（流式输出中使用） */
  cursor?: boolean
  /** 额外 className */
  className?: string
  /** 是否启用引用 [n] 链接渲染（默认 true） */
  enableCitations?: boolean
}

export default function Markdown({
  children,
  cursor = false,
  className = '',
  enableCitations = true,
}: MarkdownProps) {
  const content = cursor ? children + ' ▍' : children

  // 包装 inline 容器，对段落/列表项/引用块等的文本做引用替换
  const wrapInline = (props: { children?: React.ReactNode }) => {
    return <>{enableCitations ? renderCitationLinks(props.children) : props.children}</>
  }

  return (
    <div className={`markdown-body ${className}`}>
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        rehypePlugins={[rehypeHighlight]}
        components={{
          a: ({ href, children: aChildren, ...props }) => (
            <a href={href} target="_blank" rel="noopener noreferrer" {...props}>
              {aChildren}
            </a>
          ),
          p: (props) => <p>{wrapInline(props)}</p>,
          li: (props) => <li>{wrapInline(props)}</li>,
          blockquote: (props) => <blockquote>{wrapInline(props)}</blockquote>,
          td: (props) => <td>{wrapInline(props)}</td>,
          th: (props) => <th>{wrapInline(props)}</th>,
          // code/pre 不做替换，保持原样
        }}
      >
        {content}
      </ReactMarkdown>
    </div>
  )
}
