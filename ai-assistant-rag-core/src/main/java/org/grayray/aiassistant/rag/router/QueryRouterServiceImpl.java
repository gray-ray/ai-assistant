package org.grayray.aiassistant.rag.router;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.grayray.aiassistant.rag.expansion.QueryExpander;
import org.grayray.aiassistant.rag.model.ConversationMessage;
import org.grayray.aiassistant.rag.model.ExpansionResult;
import org.grayray.aiassistant.rag.model.QueryRouteRequest;
import org.grayray.aiassistant.rag.model.QueryRouteResult;
import org.grayray.aiassistant.rag.model.QueryType;
import org.grayray.aiassistant.rag.model.RoutedQuery;
import org.grayray.aiassistant.rag.rewrite.QueryRewriter;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * Query Router 主实现
 */
@Slf4j
@Service
public class QueryRouterServiceImpl implements QueryRouterService {

    @Resource
    private QueryClassifier classifier;

    @Resource
    private QueryRewriter rewriter;

    @Resource
    private QueryExpander expander;

    @Override
    public RoutedQuery route(QueryRouteRequest request) {
        long start = System.currentTimeMillis();
        Long sessionDbId = request == null ? null : request.getSessionId();
        Long userId = request == null ? null : request.getUserId();
        String originalQuery = request == null ? null : request.getOriginalQuery();

        // 1. 使用调用方传入的历史消息。RAG 不直接查询聊天持久化，避免模块反向依赖。
        List<ConversationMessage> history = request == null || request.getHistory() == null
                ? Collections.emptyList()
                : request.getHistory();
        String historyText = formatHistory(history);

        // 2. 历史为空（首轮）→ 直接判定 simple，跳过 LLM 调用
        QueryType type;
        String reason;
        List<String> queries;

        if (history.isEmpty()) {
            type = QueryType.SIMPLE;
            reason = "首轮对话，无历史，按 simple 处理";
            queries = List.of(originalQuery);
        } else {
            // 3. 分类
            QueryRouteResult routeResult;
            try {
                routeResult = classifier.classify(originalQuery, historyText);
                type = QueryType.fromValue(routeResult.getType());
                reason = routeResult.getReason();
            } catch (Exception e) {
                // 分类失败 → 降级 simple
                log.warn("[QueryRouter] 分类失败，降级 simple。sessionId={}, err={}",
                        sessionDbId, e.getMessage());
                type = QueryType.SIMPLE;
                reason = "分类失败降级: " + e.getMessage();
                queries = List.of(originalQuery);
                return buildAndLog(sessionDbId, userId, originalQuery, type, queries, reason, start);
            }

            // 4. 按类型分发处理
            queries = switch (type) {
                case SIMPLE -> List.of(originalQuery);
                case CONTEXTUAL -> handleRewrite(originalQuery, historyText);
                case COMPLEX -> handleExpand(originalQuery);
            };
        }

        return buildAndLog(sessionDbId, userId, originalQuery, type, queries, reason, start);
    }

    // ==================== 各类策略处理 ====================

    private List<String> handleRewrite(String originalQuery, String historyText) {
        try {
            String rewritten = rewriter.rewrite(originalQuery, historyText);
            return List.of(rewritten);
        } catch (Exception e) {
            log.warn("[QueryRouter] Rewrite 失败，回退原 query。err={}", e.getMessage());
            return List.of(originalQuery);
        }
    }

    private List<String> handleExpand(String originalQuery) {
        try {
            ExpansionResult result = expander.expand(originalQuery);
            if (result.getQueries() == null || result.getQueries().isEmpty()) {
                log.warn("[QueryRouter] Expansion 返回空，回退原 query");
                return List.of(originalQuery);
            }
            return result.getQueries();
        } catch (Exception e) {
            log.warn("[QueryRouter] Expansion 失败，回退原 query。err={}", e.getMessage());
            return List.of(originalQuery);
        }
    }

    // ==================== 历史加载与格式化 ====================

    /**
     * 将历史消息格式化为文本：User: ...\nAssistant: ...
     * 仅包含 user / assistant 角色。
     */
    String formatHistory(List<ConversationMessage> history) {
        if (history == null || history.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ConversationMessage msg : history) {
            String role = msg.getRole();
            if ("user".equals(role)) {
                sb.append("User: ").append(msg.getContent()).append("\n");
            } else if ("assistant".equals(role)) {
                sb.append("Assistant: ").append(msg.getContent()).append("\n");
            }
        }
        return sb.toString().trim();
    }

    // ==================== 输出与日志 ====================

    private RoutedQuery buildAndLog(Long sessionDbId, Long userId, String originalQuery,
                                    QueryType type, List<String> queries, String reason, long start) {
        RoutedQuery result = RoutedQuery.builder()
                .type(type)
                .originalQuery(originalQuery)
                .queries(queries)
                .routeReason(reason)
                .sessionId(sessionDbId)
                .userId(userId)
                .build();

        long cost = System.currentTimeMillis() - start;
        log.info("[QueryRouter] sessionId={}, userId={}, type={}, "
                        + "original=\"{}\", queriesCount={}, reason=\"{}\", cost={}ms",
                sessionDbId, userId, type.getValue(),
                truncate(originalQuery, 60),
                queries.size(),
                truncate(reason, 80),
                cost);

        return result;
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "...";
    }
}
