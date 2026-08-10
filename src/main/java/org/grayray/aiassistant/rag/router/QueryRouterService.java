package org.grayray.aiassistant.rag.router;

import org.grayray.aiassistant.rag.model.RoutedQuery;

/**
 * Query Router 服务：对外的唯一入口。
 * <p>
 * 路由位于"用户消息入库后、下游处理前"：
 * 根据问题特征（simple / contextual / complex）选择不同的预处理策略，
 * 统一输出 {@link RoutedQuery}，下游仅消费 {@code queries} 字段。
 */
public interface QueryRouterService {

    /**
     * 路由入口
     *
     * @param sessionDbId   chat_session 数据库主键 ID
     * @param userId        用户 ID
     * @param originalQuery 用户当前输入
     * @return 路由结果 RoutedQuery
     */
    RoutedQuery route(Long sessionDbId, Long userId, String originalQuery);
}
