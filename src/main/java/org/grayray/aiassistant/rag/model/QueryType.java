package org.grayray.aiassistant.rag.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 查询路由类型
 */
@Getter
@RequiredArgsConstructor
public enum QueryType {

    /** 简单问题：语义完整、无指代，直接透传 */
    SIMPLE("simple"),

    /** 上下文问题：依赖历史，需要重写为自包含问题 */
    CONTEXTUAL("contextual"),

    /** 复杂/模糊问题：需要拆解为多个子查询 */
    COMPLEX("complex");

    private final String value;

    /**
     * 从字符串解析 QueryType，解析失败返回 SIMPLE（降级策略）
     */
    public static QueryType fromValue(String value) {
        if (value == null || value.isBlank()) {
            return SIMPLE;
        }
        for (QueryType type : values()) {
            if (type.getValue().equalsIgnoreCase(value.trim())) {
                return type;
            }
        }
        return SIMPLE;
    }
}
