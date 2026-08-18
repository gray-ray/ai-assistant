package org.grayray.aiassistant.rag.context;

import org.grayray.aiassistant.rag.retrieval.RetrievedChunk;

import java.util.List;

/**
 * 上下文组装服务
 * <p>
 * 将检索到的片段（经过 rerank 或直接来自向量检索）组装为结构化的上下文文本，
 * 同时产出引用信息列表，供 Prompt 注入和前端展示引用来源。
 */
public interface ContextAssemblyService {

    /**
     * 将检索片段组装为上下文文本（使用默认配置的格式）
     *
     * @param chunks 已排序的片段列表（按相关性降序）
     * @param query  用户原始问题（用于上下文前导提示）
     * @return 组装结果（上下文文本 + 引用列表）
     */
    AssembledContext assemble(List<RetrievedChunk> chunks, String query);

    /**
     * 指定格式组装
     *
     * @param chunks 已排序的片段列表（按相关性降序）
     * @param query  用户原始问题
     * @param format 组装格式
     * @return 组装结果
     */
    AssembledContext assemble(List<RetrievedChunk> chunks, String query, ContextFormat format);
}
