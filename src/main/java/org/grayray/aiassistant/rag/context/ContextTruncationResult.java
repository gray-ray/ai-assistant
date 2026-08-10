package org.grayray.aiassistant.rag.context;

import lombok.Builder;
import lombok.Data;
import org.grayray.aiassistant.rag.retrieval.RetrievedChunk;

import java.util.List;

/**
 * TopN 截断结果
 */
@Data
@Builder
public class ContextTruncationResult {

    /** 最终入选的片段（已按相关性降序） */
    private List<RetrievedChunk> chunks;

    /** 入选片段总 token 数 */
    private int totalTokens;

    /** 被截断丢弃的片段数 */
    private int droppedCount;

    /** 截断原因：SCORE / COUNT / TOKEN / NONE */
    private String truncateReason;

    /** 截断原因常量 */
    public static final String REASON_NONE = "NONE";
    public static final String REASON_SCORE = "SCORE";
    public static final String REASON_COUNT = "COUNT";
    public static final String REASON_TOKEN = "TOKEN";
}
