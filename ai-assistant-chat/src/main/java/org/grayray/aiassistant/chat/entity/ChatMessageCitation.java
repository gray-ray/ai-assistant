package org.grayray.aiassistant.chat.entity;

import lombok.Data;

/**
 * 聊天消息引用来源（持久化模型）
 * <p>
 * 存储在 chat_message.citations_json JSON 列中，仅 assistant 消息有值。
 * 结构与 CitationVO 对齐，但作为独立持久化 POJO 避免实体依赖 VO 层。
 */
@Data
public class ChatMessageCitation {

    /** 引用编号（从 1 开始，对应回答中的 [n]） */
    private Integer index;

    /** 文档 ID */
    private Long documentId;

    /** 文档名称 */
    private String documentName;

    /** 章节标题（可能为 null） */
    private String chapterTitle;

    /** 片段内容（完整，用于前端悬浮展示） */
    private String content;

    /** 相关性分数 */
    private Double score;
}
