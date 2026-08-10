package org.grayray.aiassistant.rag.router;

import jakarta.annotation.PostConstruct;
import org.grayray.aiassistant.rag.model.QueryRouteResult;
import org.grayray.aiassistant.rag.rewrite.AbstractQueryComponent;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

/**
 * 查询分类器：根据当前问题及对话历史，将问题分为 simple / contextual / complex 三类。
 * 包私有：仅 QueryRouterServiceImpl 使用。
 */
@Service
public class QueryClassifier extends AbstractQueryComponent {

    @Value("classpath:prompts/query/classify.st")
    private Resource classifyTemplate;

    private String templateText;

    @Autowired
    @Qualifier("deepSeekChatModel")
    private ChatModel chatModel;

    @PostConstruct
    void init() {
        this.templateText = loadTemplate(classifyTemplate);
    }

    /**
     * 分类
     *
     * @param currentQuery 当前问题
     * @param historyText  格式化后的历史文本（为空传空串）
     * @return 分类结果；调用失败抛出异常由上层降级处理
     */
    public QueryRouteResult classify(String currentQuery, String historyText) {
        String promptText = renderTemplate(templateText,
                "recentHistory", historyText == null ? "(无历史)" : historyText,
                "currentQuery", currentQuery);

        // 第一次调用
        String raw = callChat(chatModel, promptText, 0.0);
        QueryRouteResult result = parseClassifyResult(raw);
        if (result != null) {
            return result;
        }

        // 失败重试一次
        log.warn("[QueryClassifier] 首次 JSON 解析失败，重试一次。raw={}", raw);
        String raw2 = callChat(chatModel, promptText, 0.0);
        result = parseClassifyResult(raw2);
        if (result != null) {
            return result;
        }

        throw new IllegalStateException("分类结果 JSON 解析失败: " + raw2);
    }

    private QueryRouteResult parseClassifyResult(String raw) {
        try {
            String json = extractJson(raw);
            return parseJson(json, QueryRouteResult.class);
        } catch (Exception e) {
            log.warn("[QueryClassifier] JSON 解析异常: {}", e.getMessage());
            return null;
        }
    }
}
