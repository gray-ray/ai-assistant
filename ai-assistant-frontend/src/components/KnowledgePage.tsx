/**
 * 知识库管理页面
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { ChangeEvent, FormEvent } from 'react'
import {
  createKnowledgeBase,
  deleteKnowledgeBase,
  getDocumentChunks,
  getKnowledgeBaseDetail,
  getKnowledgeBaseList,
  getKnowledgeDocuments,
  updateKnowledgeBase,
  uploadKnowledgeDocument,
} from '../api/knowledge'
import type { DocumentChunk, DocumentInfo, KnowledgeBase, KnowledgeStatus } from '../types/knowledge'
import { formatFileSize, formatTime, truncateText } from '../utils/format'
import './KnowledgePage.css'

const DEFAULT_USER_ID = 2

interface KnowledgePageProps {
  onStartChat: (knowledge: KnowledgeBase) => void
}

type NoticeType = 'success' | 'error' | 'info'

interface Notice {
  type: NoticeType
  text: string
}

const statusLabelMap: Record<string, string> = {
  ACTIVE: '启用',
  INACTIVE: '停用',
  REBUILDING: '重建中',
  pending: '等待处理',
  processing: '处理中',
  completed: '已完成',
  failed: '失败',
}

function getStatusLabel(status?: string | null) {
  if (!status) return '-'
  return statusLabelMap[status] || status
}

function getDocumentTitle(document: DocumentInfo) {
  return document.originFileName || document.fileName || `文档 ${document.id}`
}

export default function KnowledgePage({ onStartChat }: KnowledgePageProps) {
  const [knowledgeBases, setKnowledgeBases] = useState<KnowledgeBase[]>([])
  const [selectedId, setSelectedId] = useState<number | null>(null)
  const [documents, setDocuments] = useState<DocumentInfo[]>([])
  const [chunks, setChunks] = useState<DocumentChunk[]>([])
  const [selectedDocument, setSelectedDocument] = useState<DocumentInfo | null>(null)
  const [loadingBases, setLoadingBases] = useState(false)
  const [loadingDetail, setLoadingDetail] = useState(false)
  const [loadingChunks, setLoadingChunks] = useState(false)
  const [saving, setSaving] = useState(false)
  const [uploading, setUploading] = useState(false)
  const [notice, setNotice] = useState<Notice | null>(null)
  const [createName, setCreateName] = useState('')
  const [createDescription, setCreateDescription] = useState('')
  const [editName, setEditName] = useState('')
  const [editDescription, setEditDescription] = useState('')
  const [editStatus, setEditStatus] = useState<KnowledgeStatus>('ACTIVE')
  const fileInputRef = useRef<HTMLInputElement | null>(null)

  const selectedKnowledge = useMemo(
    () => knowledgeBases.find((item) => item.id === selectedId) || null,
    [knowledgeBases, selectedId],
  )

  const documentStats = useMemo(() => {
    const completed = documents.filter((doc) => doc.processStatus === 'completed').length
    const failed = documents.filter((doc) => doc.processStatus === 'failed').length
    return {
      total: documents.length,
      completed,
      failed,
    }
  }, [documents])

  const showNotice = useCallback((type: NoticeType, text: string) => {
    setNotice({ type, text })
  }, [])

  const refreshKnowledgeBases = useCallback(async () => {
    setLoadingBases(true)
    try {
      const list = await getKnowledgeBaseList(DEFAULT_USER_ID)
      list.sort((a, b) => new Date(b.updateTime).getTime() - new Date(a.updateTime).getTime())
      setKnowledgeBases(list)
      setSelectedId((current) => {
        if (current && list.some((item) => item.id === current)) return current
        return list[0]?.id ?? null
      })
    } catch (err) {
      console.error('获取知识库列表失败:', err)
      showNotice('error', '获取知识库列表失败')
      setKnowledgeBases([])
      setSelectedId(null)
    } finally {
      setLoadingBases(false)
    }
  }, [showNotice])

  const loadSelectedKnowledge = useCallback(
    async (knowledgeId: number) => {
      setLoadingDetail(true)
      setChunks([])
      setSelectedDocument(null)
      try {
        const [detail, documentList] = await Promise.all([
          getKnowledgeBaseDetail(knowledgeId, DEFAULT_USER_ID),
          getKnowledgeDocuments(knowledgeId, DEFAULT_USER_ID),
        ])
        documentList.sort(
          (a, b) => new Date(b.createTime).getTime() - new Date(a.createTime).getTime(),
        )
        setKnowledgeBases((prev) => prev.map((item) => (item.id === knowledgeId ? detail : item)))
        setDocuments(documentList)
        setEditName(detail.name)
        setEditDescription(detail.description || '')
        setEditStatus(detail.status || 'ACTIVE')
      } catch (err) {
        console.error('加载知识库详情失败:', err)
        showNotice('error', '加载知识库详情失败')
        setDocuments([])
      } finally {
        setLoadingDetail(false)
      }
    },
    [showNotice],
  )

  useEffect(() => {
    refreshKnowledgeBases()
  }, [refreshKnowledgeBases])

  useEffect(() => {
    if (selectedId) {
      loadSelectedKnowledge(selectedId)
    } else {
      setDocuments([])
      setChunks([])
      setSelectedDocument(null)
      setEditName('')
      setEditDescription('')
      setEditStatus('ACTIVE')
    }
  }, [selectedId, loadSelectedKnowledge])

  const handleCreate = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const name = createName.trim()
    if (!name) {
      showNotice('error', '请输入知识库名称')
      return
    }

    setSaving(true)
    try {
      const created = await createKnowledgeBase({
        userId: DEFAULT_USER_ID,
        name,
        description: createDescription.trim(),
      })
      setKnowledgeBases((prev) => [created, ...prev])
      setSelectedId(created.id)
      setCreateName('')
      setCreateDescription('')
      showNotice('success', '知识库已创建')
    } catch (err) {
      console.error('创建知识库失败:', err)
      showNotice('error', '创建知识库失败')
    } finally {
      setSaving(false)
    }
  }

  const handleSave = async () => {
    if (!selectedKnowledge) return
    const name = editName.trim()
    if (!name) {
      showNotice('error', '知识库名称不能为空')
      return
    }

    setSaving(true)
    try {
      const updated = await updateKnowledgeBase(selectedKnowledge.id, {
        userId: DEFAULT_USER_ID,
        name,
        description: editDescription.trim(),
        status: editStatus,
      })
      setKnowledgeBases((prev) =>
        prev.map((item) => (item.id === selectedKnowledge.id ? updated : item)),
      )
      showNotice('success', '知识库已更新')
    } catch (err) {
      console.error('更新知识库失败:', err)
      showNotice('error', '更新知识库失败')
    } finally {
      setSaving(false)
    }
  }

  const handleDelete = async () => {
    if (!selectedKnowledge) return
    const confirmed = window.confirm(`确定删除知识库「${selectedKnowledge.name}」吗？`)
    if (!confirmed) return

    setSaving(true)
    try {
      await deleteKnowledgeBase(selectedKnowledge.id, { userId: DEFAULT_USER_ID })
      setKnowledgeBases((prev) => prev.filter((item) => item.id !== selectedKnowledge.id))
      setSelectedId((current) => {
        if (current !== selectedKnowledge.id) return current
        const next = knowledgeBases.find((item) => item.id !== selectedKnowledge.id)
        return next?.id ?? null
      })
      setDocuments([])
      setChunks([])
      setSelectedDocument(null)
      showNotice('success', '知识库已删除')
    } catch (err) {
      console.error('删除知识库失败:', err)
      showNotice('error', '删除知识库失败')
    } finally {
      setSaving(false)
    }
  }

  const handleUpload = async (event: ChangeEvent<HTMLInputElement>) => {
    if (!selectedKnowledge) return
    const file = event.target.files?.[0]
    if (!file) return

    setUploading(true)
    try {
      const result = await uploadKnowledgeDocument(selectedKnowledge.id, DEFAULT_USER_ID, file)
      await loadSelectedKnowledge(selectedKnowledge.id)
      showNotice('success', `文档已上传：${result.fileName}`)
    } catch (err) {
      console.error('上传文档失败:', err)
      showNotice('error', '上传文档失败')
    } finally {
      setUploading(false)
      if (fileInputRef.current) {
        fileInputRef.current.value = ''
      }
    }
  }

  const handleOpenChunks = async (document: DocumentInfo) => {
    setSelectedDocument(document)
    setLoadingChunks(true)
    try {
      const list = await getDocumentChunks(document.id, DEFAULT_USER_ID)
      list.sort((a, b) => a.chunkIndex - b.chunkIndex)
      setChunks(list)
    } catch (err) {
      console.error('加载 Chunk 失败:', err)
      showNotice('error', '加载 Chunk 失败')
      setChunks([])
    } finally {
      setLoadingChunks(false)
    }
  }

  const canUpload = Boolean(selectedKnowledge && selectedKnowledge.status === 'ACTIVE' && !uploading)

  return (
    <div className="knowledge-page">
      <aside className="knowledge-sidebar">
        <div className="knowledge-sidebar-header">
          <div>
            <p className="knowledge-eyebrow">Knowledge</p>
            <h2>知识库</h2>
          </div>
          <button
            className="knowledge-icon-btn"
            onClick={refreshKnowledgeBases}
            disabled={loadingBases}
            title="刷新"
          >
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <path d="M21 12a9 9 0 1 1-2.64-6.36" />
              <path d="M21 3v6h-6" />
            </svg>
          </button>
        </div>

        <form className="knowledge-create-form" onSubmit={handleCreate}>
          <input
            value={createName}
            onChange={(event) => setCreateName(event.target.value)}
            maxLength={100}
            placeholder="新知识库名称"
            disabled={saving}
          />
          <textarea
            value={createDescription}
            onChange={(event) => setCreateDescription(event.target.value)}
            maxLength={500}
            placeholder="描述"
            disabled={saving}
          />
          <button className="knowledge-primary-btn" type="submit" disabled={saving}>
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <path d="M12 5v14M5 12h14" />
            </svg>
            新建知识库
          </button>
        </form>

        <div className="knowledge-list">
          {loadingBases && knowledgeBases.length === 0 && (
            <div className="knowledge-empty">加载中...</div>
          )}

          {!loadingBases && knowledgeBases.length === 0 && (
            <div className="knowledge-empty">
              <p>暂无知识库</p>
              <span>创建后即可上传文档</span>
            </div>
          )}

          {knowledgeBases.map((knowledge) => (
            <button
              key={knowledge.id}
              className={`knowledge-list-item ${knowledge.id === selectedId ? 'active' : ''}`}
              onClick={() => setSelectedId(knowledge.id)}
              type="button"
            >
              <span className="knowledge-list-title">{truncateText(knowledge.name, 18)}</span>
              <span className={`knowledge-status-dot status-${knowledge.status.toLowerCase()}`} />
              <span className="knowledge-list-meta">{formatTime(knowledge.updateTime)}</span>
            </button>
          ))}
        </div>
      </aside>

      <main className="knowledge-main">
        <div className="knowledge-toolbar">
          <div>
            <h1>{selectedKnowledge?.name || '知识库管理'}</h1>
            <p>{selectedKnowledge?.description || '管理知识库、文档与切分片段'}</p>
          </div>
          {selectedKnowledge && (
            <div className="knowledge-toolbar-actions">
              <button
                className="knowledge-secondary-btn"
                onClick={() => onStartChat(selectedKnowledge)}
                type="button"
              >
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
                </svg>
                基于此库对话
              </button>
              <label className={`knowledge-upload-btn ${!canUpload ? 'disabled' : ''}`}>
                <input
                  ref={fileInputRef}
                  type="file"
                  onChange={handleUpload}
                  disabled={!canUpload}
                />
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
                  <path d="M17 8l-5-5-5 5" />
                  <path d="M12 3v12" />
                </svg>
                {uploading ? '上传中' : '上传文档'}
              </label>
            </div>
          )}
        </div>

        {notice && (
          <div className={`knowledge-notice notice-${notice.type}`}>
            <span>{notice.text}</span>
            <button onClick={() => setNotice(null)} type="button" title="关闭">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <path d="M18 6 6 18M6 6l12 12" />
              </svg>
            </button>
          </div>
        )}

        {selectedKnowledge ? (
          <div className="knowledge-content">
            <section className="knowledge-detail-panel">
              <div className="knowledge-panel-header">
                <h2>基础信息</h2>
                <div className="knowledge-detail-actions">
                  <button
                    className="knowledge-primary-btn compact"
                    onClick={handleSave}
                    disabled={saving || loadingDetail}
                    type="button"
                  >
                    保存
                  </button>
                  <button
                    className="knowledge-danger-btn compact"
                    onClick={handleDelete}
                    disabled={saving || loadingDetail}
                    type="button"
                  >
                    删除
                  </button>
                </div>
              </div>

              <div className="knowledge-edit-grid">
                <label>
                  <span>名称</span>
                  <input
                    value={editName}
                    onChange={(event) => setEditName(event.target.value)}
                    maxLength={100}
                  />
                </label>
                <label>
                  <span>状态</span>
                  <select
                    value={editStatus}
                    onChange={(event) => setEditStatus(event.target.value)}
                  >
                    <option value="ACTIVE">启用</option>
                    <option value="INACTIVE">停用</option>
                    <option value="REBUILDING">重建中</option>
                  </select>
                </label>
                <label className="knowledge-wide-field">
                  <span>描述</span>
                  <textarea
                    value={editDescription}
                    onChange={(event) => setEditDescription(event.target.value)}
                    maxLength={500}
                  />
                </label>
              </div>

              <div className="knowledge-meta-row">
                <div>
                  <span>文档</span>
                  <strong>{documentStats.total}</strong>
                </div>
                <div>
                  <span>完成</span>
                  <strong>{documentStats.completed}</strong>
                </div>
                <div>
                  <span>失败</span>
                  <strong>{documentStats.failed}</strong>
                </div>
                <div>
                  <span>向量库</span>
                  <strong>{selectedKnowledge.vectorStoreType || '-'}</strong>
                </div>
              </div>
            </section>

            <section className="knowledge-documents-panel">
              <div className="knowledge-panel-header">
                <h2>文档</h2>
                <span>{loadingDetail ? '加载中...' : `${documents.length} 个文档`}</span>
              </div>

              {documents.length === 0 ? (
                <div className="knowledge-document-empty">
                  <p>当前知识库还没有文档</p>
                  <span>上传完成后会自动解析、切分并写入向量库</span>
                </div>
              ) : (
                <div className="knowledge-table-wrap">
                  <table className="knowledge-table">
                    <thead>
                      <tr>
                        <th>文件</th>
                        <th>状态</th>
                        <th>大小</th>
                        <th>上传时间</th>
                        <th>操作</th>
                      </tr>
                    </thead>
                    <tbody>
                      {documents.map((document) => (
                        <tr key={document.id}>
                          <td>
                            <div className="document-name-cell">
                              <strong>{getDocumentTitle(document)}</strong>
                              <span>{document.fileType || document.fileName}</span>
                            </div>
                          </td>
                          <td>
                            <span className={`document-status status-${(document.processStatus || '').toLowerCase()}`}>
                              {getStatusLabel(document.processStatus)}
                            </span>
                          </td>
                          <td>{formatFileSize(document.fileSize)}</td>
                          <td>{formatTime(document.createTime)}</td>
                          <td>
                            <button
                              className="knowledge-link-btn"
                              onClick={() => handleOpenChunks(document)}
                              type="button"
                            >
                              查看 Chunk
                            </button>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </section>
          </div>
        ) : (
          <div className="knowledge-blank-state">
            <h2>创建一个知识库</h2>
            <p>左侧填写名称后即可开始整理文档。</p>
          </div>
        )}
      </main>

      {selectedDocument && (
        <aside className="chunk-drawer">
          <div className="chunk-drawer-header">
            <div>
              <p>Document Chunks</p>
              <h2>{getDocumentTitle(selectedDocument)}</h2>
            </div>
            <button onClick={() => setSelectedDocument(null)} type="button" title="关闭">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <path d="M18 6 6 18M6 6l12 12" />
              </svg>
            </button>
          </div>

          <div className="chunk-list">
            {loadingChunks && <div className="knowledge-empty">加载 Chunk 中...</div>}
            {!loadingChunks && chunks.length === 0 && (
              <div className="knowledge-empty">暂无 Chunk</div>
            )}
            {chunks.map((chunk) => (
              <article className="chunk-item" key={chunk.id}>
                <div className="chunk-item-header">
                  <span>#{chunk.chunkIndex + 1}</span>
                  <span>{chunk.tokenCount ? `${chunk.tokenCount} tokens` : 'tokens -'}</span>
                </div>
                {chunk.chapterTitle && <h3>{chunk.chapterTitle}</h3>}
                <p>{chunk.content}</p>
                <div className="chunk-meta">
                  <span>版本 {chunk.chunkVersion ?? '-'}</span>
                  <span>页码 {chunk.pageNumber ?? '-'}</span>
                </div>
              </article>
            ))}
          </div>
        </aside>
      )}
    </div>
  )
}
