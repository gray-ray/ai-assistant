package org.grayray.aiassistant.rag.context;

/**
 * 上下文组装格式枚举
 */
public enum ContextFormat {

    /** 编号引用 [1] [2] ... —— 默认格式，LLM 易学会标注引用 */
    NUMBERED,

    /** Markdown 标题 + 引用块 */
    MARKDOWN,

    /** 纯文本拼接（仅内容，无编号元数据） */
    PLAIN;

    /**
     * 从字符串解析格式（不区分大小写）
     */
    public static ContextFormat fromString(String value) {
        if (value == null || value.isBlank()) {
            return NUMBERED;
        }
        return switch (value.trim().toLowerCase()) {
            case "numbered" -> NUMBERED;
            case "markdown" -> MARKDOWN;
            case "plain" -> PLAIN;
            default -> NUMBERED;
        };
    }
}
