/**
 * 时间格式化工具
 * 后端返回格式: yyyy-MM-ddTHH:mm:ss (如 "2026-08-08T10:30:00")
 */

/**
 * 格式化时间为友好展示
 * - 今天: HH:mm
 * - 昨天: 昨天 HH:mm
 * - 今年: MM-dd HH:mm
 * - 其他: yyyy-MM-dd HH:mm
 */
export function formatTime(timeStr: string): string {
  if (!timeStr) return ''
  const date = new Date(timeStr)
  if (Number.isNaN(date.getTime())) return timeStr

  const now = new Date()
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  const yesterday = new Date(today.getTime() - 86400000)
  const targetDay = new Date(date.getFullYear(), date.getMonth(), date.getDate())

  const hh = String(date.getHours()).padStart(2, '0')
  const mm = String(date.getMinutes()).padStart(2, '0')
  const timePart = `${hh}:${mm}`

  if (targetDay.getTime() === today.getTime()) {
    return timePart
  }
  if (targetDay.getTime() === yesterday.getTime()) {
    return `昨天 ${timePart}`
  }
  const MM = String(date.getMonth() + 1).padStart(2, '0')
  const dd = String(date.getDate()).padStart(2, '0')
  if (date.getFullYear() === now.getFullYear()) {
    return `${MM}-${dd} ${timePart}`
  }
  return `${date.getFullYear()}-${MM}-${dd} ${timePart}`
}

/**
 * 会话列表时间显示（更简洁）
 * - 今天: HH:mm
 * - 昨天: 昨天
 * - 本周: 周X
 * - 其他: MM/dd
 */
export function formatSessionTime(timeStr: string): string {
  if (!timeStr) return ''
  const date = new Date(timeStr)
  if (Number.isNaN(date.getTime())) return timeStr

  const now = new Date()
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  const targetDay = new Date(date.getFullYear(), date.getMonth(), date.getDate())
  const diffDays = Math.floor((today.getTime() - targetDay.getTime()) / 86400000)

  const hh = String(date.getHours()).padStart(2, '0')
  const mm = String(date.getMinutes()).padStart(2, '0')

  if (diffDays === 0) return `${hh}:${mm}`
  if (diffDays === 1) return '昨天'
  if (diffDays < 7) {
    const weekDays = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']
    return weekDays[date.getDay()]
  }
  const MM = String(date.getMonth() + 1).padStart(2, '0')
  const dd = String(date.getDate()).padStart(2, '0')
  return `${MM}/${dd}`
}

/**
 * 截断文本（用于会话列表预览）
 */
export function truncateText(text: string, maxLen = 20): string {
  if (!text) return ''
  if (text.length <= maxLen) return text
  return text.slice(0, maxLen) + '…'
}