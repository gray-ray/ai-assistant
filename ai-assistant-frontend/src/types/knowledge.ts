/**
 * 知识库相关 TypeScript 类型定义
 */

export type KnowledgeStatus = 'ACTIVE' | 'INACTIVE' | 'REBUILDING' | string
export type DocumentProcessStatus = 'pending' | 'processing' | 'completed' | 'failed' | string

export interface KnowledgeBase {
  id: number
  userId: number
  name: string
  description?: string | null
  vectorStoreType?: string | null
  vectorStorePath?: string | null
  vectorCollection?: string | null
  status: KnowledgeStatus
  createTime: string
  updateTime: string
}

export interface KnowledgeBaseCreateRequest {
  userId: number
  name: string
  description?: string
}

export interface KnowledgeBaseUpdateRequest {
  userId: number
  name?: string
  description?: string
  status?: KnowledgeStatus
}

export interface KnowledgeBaseDeleteRequest {
  userId: number
}

export interface DocumentUploadResult {
  documentId: number
  fileName: string
  fileSize: number
  fileUrl?: string | null
  processStatus: DocumentProcessStatus
}

export interface DocumentInfo {
  id: number
  knowledgeId?: number | null
  fileUrl?: string | null
  fileName: string
  fileType?: string | null
  fileSize?: number | null
  originFileName?: string | null
  storageType?: string | null
  storagePath?: string | null
  sessionId?: number | null
  userId: number
  messageId?: number | null
  processStatus?: DocumentProcessStatus | null
  processError?: string | null
  createTime: string
  isDeleted?: number
}

export interface DocumentChunk {
  id: number
  chunkId: string
  documentId: number
  knowledgeId: number
  chunkVersion?: number | null
  chunkIndex: number
  totalChunks?: number | null
  content: string
  contentHash?: string | null
  pageNumber?: number | null
  chapterIndex?: number | null
  chapterTitle?: string | null
  tokenCount?: number | null
  vectorId?: string | null
  metadata?: Record<string, unknown> | null
  createTime: string
  updateTime?: string | null
  isDeleted?: number
}
