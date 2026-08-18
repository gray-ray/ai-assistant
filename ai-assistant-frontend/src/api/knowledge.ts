/**
 * 知识库相关 API
 */
import request from '../utils/request'
import type {
  DocumentChunk,
  DocumentInfo,
  DocumentUploadResult,
  KnowledgeBase,
  KnowledgeBaseCreateRequest,
  KnowledgeBaseDeleteRequest,
  KnowledgeBaseUpdateRequest,
} from '../types/knowledge'

/** 创建知识库 */
export function createKnowledgeBase(data: KnowledgeBaseCreateRequest) {
  return request.post<KnowledgeBase>('/knowledge/create', data)
}

/** 获取用户知识库列表 */
export function getKnowledgeBaseList(userId: number) {
  return request.get<KnowledgeBase[]>(`/knowledge/list?userId=${userId}`)
}

/** 获取知识库详情 */
export function getKnowledgeBaseDetail(knowledgeId: number, userId: number) {
  return request.get<KnowledgeBase>(`/knowledge/${knowledgeId}?userId=${userId}`)
}

/** 更新知识库 */
export function updateKnowledgeBase(knowledgeId: number, data: KnowledgeBaseUpdateRequest) {
  return request.post<KnowledgeBase>(`/knowledge/${knowledgeId}/update`, data)
}

/** 删除知识库 */
export function deleteKnowledgeBase(knowledgeId: number, data: KnowledgeBaseDeleteRequest) {
  return request.post<void>(`/knowledge/${knowledgeId}/delete`, data)
}

/** 上传文档到知识库 */
export function uploadKnowledgeDocument(knowledgeId: number, userId: number, file: File) {
  const formData = new FormData()
  formData.append('file', file)

  return request.post<DocumentUploadResult>(
    `/knowledge/${knowledgeId}/document/upload?userId=${userId}`,
    formData,
    {
      headers: {
        'Content-Type': 'multipart/form-data',
      },
    },
  )
}

/** 获取知识库文档列表 */
export function getKnowledgeDocuments(knowledgeId: number, userId: number) {
  return request.get<DocumentInfo[]>(`/knowledge/${knowledgeId}/documents?userId=${userId}`)
}

/** 获取文档 Chunk 列表 */
export function getDocumentChunks(documentId: number, userId: number) {
  return request.get<DocumentChunk[]>(`/knowledge/document/${documentId}/chunks?userId=${userId}`)
}
