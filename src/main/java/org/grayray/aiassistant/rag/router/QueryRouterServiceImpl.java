package org.grayray.aiassistant.rag.router;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.grayray.aiassistant.rag.expansion.QueryExpander;
import org.grayray.aiassistant.rag.model.ExpansionResult;
import org.grayray.aiassistant.rag.model.QueryRouteResult;
import org.grayray.aiassistant.rag.model.QueryType;
import org.grayray.aiassistant.rag.model.RoutedQuery;
import org.grayray.aiassistant.rag.rewrite.QueryRewriter;
import org.grayray.aiassistant.chat.entity.ChatMessage;
import org.grayray.aiassistant.chat.mapper.ChatMessageMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Query Router 主实现
 */
@Slf4j
@Service
public class QueryRouterServiceImpl implements QueryRouterService {

    /** 加载最近 N 轮历史对话 */
    private static final int RECENT_HISTORY_ROUNDS = 5;

    @Resource
    private ChatMessageMapper chatMessageMapper;

    @Resource
    private QueryClassifier classifier;

    @Resource
    private QueryRewriter rewriter;

    @Resource
    private QueryExpander expander;

    @Override
    public RoutedQuery route(Long sessionDbId, Long userId, String originalQuery) {
        long start = System.currentTimeMillis();

        // 1. 加载历史消息（不包含刚入库的当前用户消息，按 message_index 升序）
        List<ChatMessage> history = loadRecentHistory(sessionDbId, RECENT_HISTORY_ROUNDS);
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
     * 加载最近 N 轮历史对话（不包含当前刚插入的用户消息）。
     * <p>
     * route() 调用时机是"用户消息入库后"，此时最新一条是刚插入的用户消息。
     * 策略：按 message_index 倒序跳过最新 1 条，取后续 rounds*2 条，然后反转为升序。
     * 同时过滤仅保留 user / assistant 角色。
     */
    List<ChatMessage> loadRecentHistory(Long sessionDbId, int rounds) {
        // 多取 1 条（因为需要跳过最新的用户消息）
        int fetchSize = rounds * 2 + 1;
        List<ChatMessage> descList = chatMessageMapper.selectList(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getSessionId, sessionDbId)
                        .in(ChatMessage::getRole, "user", "assistant")
                        .orderByDesc(ChatMessage::getMessageIndex)
                        .last("LIMIT " + fetchSize));

        if (descList == null || descList.isEmpty()) {
            return Collections.emptyList();
        }

        // 跳过最新 1 条（即刚插入的当前用户消息），剩余的是历史
        List<ChatMessage> historyDesc = descList.size() > 1
                ? new ArrayList<>(descList.subList(1, descList.size()))
                : Collections.<ChatMessage>emptyList();

        if (historyDesc.isEmpty()) {
            return Collections.emptyList();
        }

        // 反转成升序（按 message_index 从小到大）
        List<ChatMessage> ascList = new ArrayList<>(historyDesc);
        Collections.reverse(ascList);
        return ascList;
    }

    /**
     * 将历史消息格式化为文本：User: ...\nAssistant: ...
     * 仅包含 user / assistant 角色。
     */
    String formatHistory(List<ChatMessage> history) {
        if (history == null || history.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ChatMessage msg : history) {
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
