/**
 * 正文引用标记组件
 *
 * 将正文中的 [1]、[2] 等编号渲染为可点击的角标，点击后滚动到对应引用项并高亮。
 */
import {
  type ReactNode,
  Children,
  isValidElement,
  cloneElement,
} from 'react'

export const CITATION_REGEX = /\[(\d+)\]/g

interface CitationRefProps {
  index: number
  onClick?: (index: number) => void
}

export function CitationRef({ index }: CitationRefProps) {
  const handleClick = (e: React.MouseEvent) => {
    e.preventDefault()
    // 滚动到引用列表对应项
    const target = document.querySelector(
      `.citation-item[data-citation-index="${index}"]`,
    )
    if (target) {
      target.scrollIntoView({ behavior: 'smooth', block: 'center' })
      target.classList.add('is-flash')
      setTimeout(() => target.classList.remove('is-flash'), 1500)
    }
  }

  return (
    <button
      type="button"
      className="citation-ref"
      data-citation-ref={index}
      onClick={handleClick}
      title={`引用来源 [${index}]`}
    >
      {index}
    </button>
  )
}

/**
 * 递归遍历 React children，将文本节点中形如 [1] [2] 的引用标记
 * 替换为 <CitationRef /> 组件。
 *
 * 不会替换 code / pre 内部的文本。
 */
export function renderCitationLinks(
  children: ReactNode,
): ReactNode {
  return Children.map(children, (child) => {
    if (typeof child === 'string') {
      return splitByCitation(child)
    }
    if (isValidElement<{ children?: ReactNode }>(child)) {
      const tagName = typeof child.type === 'string' ? child.type : ''
      if (tagName === 'code' || tagName === 'pre') {
        return child
      }
      return cloneElement(child, {
        children: renderCitationLinks(child.props.children),
      })
    }
    return child
  })
}

function splitByCitation(text: string): ReactNode[] {
  const parts: ReactNode[] = []
  let lastIndex = 0
  let match: RegExpExecArray | null
  const re = new RegExp(CITATION_REGEX.source, 'g')
  let key = 0
  while ((match = re.exec(text)) !== null) {
    if (match.index > lastIndex) {
      parts.push(text.slice(lastIndex, match.index))
    }
    const idx = parseInt(match[1], 10)
    parts.push(<CitationRef key={`cr-${key++}`} index={idx} />)
    lastIndex = re.lastIndex
  }
  if (lastIndex < text.length) {
    parts.push(text.slice(lastIndex))
  }
  return parts
}
