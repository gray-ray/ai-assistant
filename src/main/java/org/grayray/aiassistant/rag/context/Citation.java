package org.grayray.aiassistant.rag.context;

import lombok.Builder;
import lombok.Data;

/**
 * 引用信息
 * <p>
 * 对应上下文中的一个文档片段，按编号顺序排列，与上下文中的 [n] 一一对应。
 * 供前端在回答末尾或悬浮时展示来源详情。
 */
@Data
@Builder
public class Citation {

    /** 引用编号（从 1 开始，对应上下文中的 [n]） */
    private int index;

    /** 片段 ID */
    private String chunkId;

    /** 文档 ID */
    private Long documentId;

    /** 文档名称 */
    private String documentName;

    /** 章节标题（可能为 null） */
    private String chapterTitle;

    /** 章节序号（可能为 null） */
    private Integer chapterIndex;

    /** 片段在文档中的序号 */
    private Integer chunkIndex;

    /** 片段内容（完整，用于前端悬浮展示） */
    private String content;

    /** 相关性分数（用于排序展示） */
    private double score;
}
