package org.grayray.aiassistant.rag.router;

import org.grayray.aiassistant.rag.expansion.QueryExpander;
import org.grayray.aiassistant.rag.model.ConversationMessage;
import org.grayray.aiassistant.rag.model.ExpansionResult;
import org.grayray.aiassistant.rag.model.QueryRouteRequest;
import org.grayray.aiassistant.rag.model.QueryRouteResult;
import org.grayray.aiassistant.rag.model.QueryType;
import org.grayray.aiassistant.rag.model.RoutedQuery;
import org.grayray.aiassistant.rag.rewrite.QueryRewriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * QueryRouterService 单元测试：mock 掉 Classifier/Rewriter/Expander，
 * 覆盖三种路由路径 + 首轮 simple 直通 + 降级场景。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QueryRouterServiceTest {

    @Mock
    private QueryClassifier classifier;

    @Mock
    private QueryRewriter rewriter;

    @Mock
    private QueryExpander expander;

    @InjectMocks
    private QueryRouterServiceImpl routerService;

    private static final Long SESSION_DB_ID = 100L;
    private static final Long USER_ID = 1L;
    private List<ConversationMessage> defaultHistory;

    @BeforeEach
    void setUp() {
        defaultHistory = List.of(
                new ConversationMessage("user", "介绍下 Spring AI"),
                new ConversationMessage("assistant", "Spring AI 是一个 AI 应用框架..."));
    }

    @Test
    @DisplayName("首轮对话（无历史）→ 直接返回 SIMPLE，不调用 classifier/LLM")
    void testFirstTurnDirectSimple() {
        RoutedQuery result = routerService.route(request("你好", Collections.emptyList()));

        assertEquals(QueryType.SIMPLE, result.getType());
        assertEquals(1, result.getQueries().size());
        assertEquals("你好", result.getQueries().get(0));
        assertEquals("你好", result.getOriginalQuery());
        assertEquals(SESSION_DB_ID, result.getSessionId());
        assertEquals(USER_ID, result.getUserId());
        // 不应调用分类器
        verifyNoInteractions(classifier);
        verifyNoInteractions(rewriter);
        verifyNoInteractions(expander);
    }

    @Test
    @DisplayName("simple 类型 → 原 query 透传")
    void testSimplePassthrough() {
        QueryRouteResult route = new QueryRouteResult();
        route.setType("simple");
        route.setReason("问题语义完整");
        when(classifier.classify(eq("杭州今天天气怎么样？"), anyString())).thenReturn(route);

        RoutedQuery result = routerService.route(request("杭州今天天气怎么样？", defaultHistory));

        assertEquals(QueryType.SIMPLE, result.getType());
        assertEquals(1, result.getQueries().size());
        assertEquals("杭州今天天气怎么样？", result.getQueries().get(0));
        verifyNoInteractions(rewriter);
        verifyNoInteractions(expander);
    }

    @Test
    @DisplayName("contextual 类型 → 调用 rewriter 重写，返回单个 rewritten query")
    void testContextualRewrite() {
        QueryRouteResult route = new QueryRouteResult();
        route.setType("contextual");
        route.setReason("包含代词'它'");
        when(classifier.classify(eq("它支持哪些向量库？"), anyString())).thenReturn(route);
        when(rewriter.rewrite(eq("它支持哪些向量库？"), anyString()))
                .thenReturn("Spring AI 支持哪些向量库？");

        RoutedQuery result = routerService.route(request("它支持哪些向量库？", defaultHistory));

        assertEquals(QueryType.CONTEXTUAL, result.getType());
        assertEquals(1, result.getQueries().size());
        assertEquals("Spring AI 支持哪些向量库？", result.getQueries().get(0));
        verify(rewriter).rewrite(anyString(), anyString());
        verifyNoInteractions(expander);
    }

    @Test
    @DisplayName("complex 类型 → 调用 expander 返回多个子查询")
    void testComplexExpansion() {
        QueryRouteResult route = new QueryRouteResult();
        route.setType("complex");
        route.setReason("问题宽泛含多意图");
        when(classifier.classify(eq("怎么做好一个AI助手"), anyString())).thenReturn(route);

        ExpansionResult expansion = new ExpansionResult();
        expansion.setQueries(Arrays.asList(
                "AI 助手的系统架构如何设计？",
                "AI 助手如何管理多轮对话上下文？",
                "AI 助手常见的安全与对齐问题有哪些？",
                "评估 AI 助手回答质量的方法有哪些？"));
        expansion.setReason("从架构、上下文、安全、评估四个维度拆解");
        when(expander.expand("怎么做好一个AI助手")).thenReturn(expansion);

        RoutedQuery result = routerService.route(request("怎么做好一个AI助手", defaultHistory));

        assertEquals(QueryType.COMPLEX, result.getType());
        assertEquals(4, result.getQueries().size());
        assertEquals("AI 助手的系统架构如何设计？", result.getQueries().get(0));
        verify(expander).expand("怎么做好一个AI助手");
        verifyNoInteractions(rewriter);
    }

    @Test
    @DisplayName("分类器抛异常 → 降级为 simple 返回原 query")
    void testClassifierFailFallback() {
        when(classifier.classify(anyString(), anyString()))
                .thenThrow(new RuntimeException("DeepSeek API 超时"));

        RoutedQuery result = routerService.route(request("测试问题", defaultHistory));

        assertEquals(QueryType.SIMPLE, result.getType());
        assertEquals(1, result.getQueries().size());
        assertEquals("测试问题", result.getQueries().get(0));
        assertTrue(result.getRouteReason().contains("失败降级"));
        verifyNoInteractions(rewriter);
        verifyNoInteractions(expander);
    }

    @Test
    @DisplayName("rewriter 抛异常 → 降级返回原 query")
    void testRewriterFailFallback() {
        QueryRouteResult route = new QueryRouteResult();
        route.setType("contextual");
        route.setReason("上下文依赖");
        when(classifier.classify(anyString(), anyString())).thenReturn(route);
        when(rewriter.rewrite(anyString(), anyString()))
                .thenThrow(new IllegalStateException("Rewrite 返回空"));

        RoutedQuery result = routerService.route(request("它怎么样", defaultHistory));

        assertEquals(QueryType.CONTEXTUAL, result.getType(), "类型仍应为 contextual（记录了路由意图）");
        assertEquals(1, result.getQueries().size());
        assertEquals("它怎么样", result.getQueries().get(0), "rewrite 失败时回退到原 query");
    }

    @Test
    @DisplayName("expander 返回空 queries → 降级返回原 query")
    void testExpanderEmptyFallback() {
        QueryRouteResult route = new QueryRouteResult();
        route.setType("complex");
        route.setReason("复杂问题");
        when(classifier.classify(anyString(), anyString())).thenReturn(route);

        ExpansionResult empty = new ExpansionResult();
        empty.setQueries(Collections.emptyList());
        when(expander.expand(anyString())).thenReturn(empty);

        RoutedQuery result = routerService.route(request("讲讲 Redis", defaultHistory));

        assertEquals(QueryType.COMPLEX, result.getType());
        assertEquals(1, result.getQueries().size());
        assertEquals("讲讲 Redis", result.getQueries().get(0));
    }

    @Test
    @DisplayName("expander 抛异常 → 降级返回原 query")
    void testExpanderExceptionFallback() {
        QueryRouteResult route = new QueryRouteResult();
        route.setType("complex");
        route.setReason("复杂问题");
        when(classifier.classify(anyString(), anyString())).thenReturn(route);
        when(expander.expand(anyString())).thenThrow(new RuntimeException("扩展失败"));

        RoutedQuery result = routerService.route(request("讲讲 Java", defaultHistory));

        assertEquals(QueryType.COMPLEX, result.getType());
        assertEquals(1, result.getQueries().size());
        assertEquals("讲讲 Java", result.getQueries().get(0));
    }

    @Test
    @DisplayName("formatHistory: 仅保留 user/assistant，按顺序拼接")
    void testFormatHistory() {
        ConversationMessage sys = new ConversationMessage("system", "你是AI助手");
        ConversationMessage user = new ConversationMessage("user", "你好");
        ConversationMessage ai = new ConversationMessage("assistant", "你好！有什么可以帮你？");

        List<ConversationMessage> msgs = Arrays.asList(sys, user, ai);
        String text = routerService.formatHistory(msgs);

        assertTrue(text.contains("User: 你好"));
        assertTrue(text.contains("Assistant: 你好！有什么可以帮你？"));
        assertFalse(text.contains("system"));
        assertFalse(text.contains("你是AI助手"));
    }

    @Test
    @DisplayName("formatHistory: 空列表返回空串")
    void testFormatHistoryEmpty() {
        assertEquals("", routerService.formatHistory(Collections.emptyList()));
        assertEquals("", routerService.formatHistory(null));
    }

    // ==================== 辅助方法 ====================

    private QueryRouteRequest request(String query, List<ConversationMessage> history) {
        return QueryRouteRequest.builder()
                .sessionId(SESSION_DB_ID)
                .userId(USER_ID)
                .originalQuery(query)
                .history(history)
                .build();
    }
}
