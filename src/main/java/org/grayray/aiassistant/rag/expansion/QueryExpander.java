package org.grayray.aiassistant.rag.expansion;

import jakarta.annotation.PostConstruct;
import org.grayray.aiassistant.rag.model.ExpansionResult;
import org.grayray.aiassistant.rag.rewrite.AbstractQueryComponent;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Query Expander：将宽泛、模糊或多意图的单一问题拆解为 2~4 个互补子查询。
 * 包私有：仅 QueryRouterServiceImpl 使用。
 */
@Service
public class QueryExpander extends AbstractQueryComponent {

    /** 最大子查询数（超过截断） */
    private static final int MAX_QUERIES = 6;

    @Value("classpath:prompts/query/expand.st")
    private Resource expandTemplate;

    private String templateText;

    @Autowired
    @Qualifier("deepSeekChatModel")
    private ChatModel chatModel;

    @PostConstruct
    void init() {
        this.templateText = loadTemplate(expandTemplate);
    }

    /**
     * 扩展问题
     *
     * @param currentQuery 当前问题
     * @return 扩展结果；调用失败或解析失败时抛出异常由上层降级
     */
    public ExpansionResult expand(String currentQuery) {
        String promptText = renderTemplate(templateText,
                "currentQuery", currentQuery);

        String raw = callChat(chatModel, promptText, 0.3);
        ExpansionResult result = parseExpandResult(raw);
        if (result != null && isResultValid(result)) {
            return sanitizeResult(result);
        }

        // 重试一次
        log.warn("[QueryExpander] 首次结果不合法，重试一次。raw={}", raw);
        String raw2 = callChat(chatModel, promptText, 0.3);
        ExpansionResult result2 = parseExpandResult(raw2);
        if (result2 != null && isResultValid(result2)) {
            return sanitizeResult(result2);
        }

        throw new IllegalStateException("扩展结果不合法: " + raw2);
    }

    private ExpansionResult parseExpandResult(String raw) {
        try {
            String json = extractJson(raw);
            return parseJson(json, ExpansionResult.class);
        } catch (Exception e) {
            log.warn("[QueryExpander] JSON 解析异常: {}", e.getMessage());
            return null;
        }
    }

    private boolean isResultValid(ExpansionResult result) {
        return result.getQueries() != null && !result.getQueries().isEmpty();
    }

    /**
     * 结果清洗：过滤空串、去重、截断超限
     */
    private ExpansionResult sanitizeResult(ExpansionResult result) {
        List<String> cleaned = new ArrayList<>();
        for (String q : result.getQueries()) {
            if (q == null || q.isBlank()) {
                continue;
            }
            if (!cleaned.contains(q)) {
                cleaned.add(q);
            }
        }
        // 超过最大数截断
        if (cleaned.size() > MAX_QUERIES) {
            log.warn("[QueryExpander] 子查询数量 {} 超过上限 {}，截断为 {}",
                    cleaned.size(), MAX_QUERIES, MAX_QUERIES);
            cleaned = new ArrayList<>(cleaned.subList(0, MAX_QUERIES));
        }
        result.setQueries(cleaned);
        return result;
    }
}
