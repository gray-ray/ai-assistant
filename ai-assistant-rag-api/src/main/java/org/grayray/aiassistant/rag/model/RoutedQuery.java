package org.grayray.aiassistant.rag.model;

import lombok.Builder;
import lombok.Data;
import org.grayray.aiassistant.rag.model.QueryType;

import java.util.List;

/**
 * Query Router 统一输出结构，下游模块消费 queries 字段即可。
 */
@Data
@Builder
public class RoutedQuery {

    /** 路由类型：simple / contextual / complex */
    private QueryType type;

    /** 用户原始问题（原样保留） */
    private String originalQuery;

    /** 路由处理后的查询列表（1~4 条） */
    private List<String> queries;

    /** 分类/拆解理由，便于日志与调试 */
    private String routeReason;

    /** 关联 chat_session.id（数据库主键） */
    private Long sessionId;

    /** 用户 id */
    private Long userId;
}
