package org.grayray.aiassistant.rag.context;

import lombok.Builder;
import lombok.Data;

import java.util.Collections;
import java.util.List;

/**
 * 上下文组装结果
 * <p>
 * 包含组装好的完整上下文文本（可直接注入 Prompt），
 * 以及对应的引用信息列表（供前端展示来源）。
 */
@Data
@Builder
public class AssembledContext {

    /** 组装后的完整上下文文本（含前导说明 + 各片段） */
    private String text;

    /** 引用列表（按编号顺序，与 text 中 [n] 对应） */
    private List<Citation> citations;

    /** 总 token 数（所有片段 content tokenCount 之和） */
    private int totalTokens;

    /** 片段数量 */
    private int chunkCount;

    /** 组装格式 */
    private ContextFormat format;

    /** 截断原因（来自 ContextTruncationResult） */
    private String truncateReason;

    /** 被截断丢弃的片段数 */
    private int droppedCount;

    /**
     * 是否为空（无片段时为 true）
     */
    public boolean isEmpty() {
        return chunkCount == 0 || citations == null || citations.isEmpty();
    }

    /**
     * 创建空上下文（无检索结果或组装失败时使用）
     */
    public static AssembledContext empty(ContextFormat format) {
        return AssembledContext.builder()
                .text("")
                .citations(Collections.emptyList())
                .totalTokens(0)
                .chunkCount(0)
                .format(format)
                .truncateReason(ContextTruncationResult.REASON_NONE)
                .droppedCount(0)
                .build();
    }
}
