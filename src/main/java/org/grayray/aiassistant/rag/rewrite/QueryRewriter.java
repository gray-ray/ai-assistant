package org.grayray.aiassistant.rag.rewrite;

import jakarta.annotation.PostConstruct;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

/**
 * Query Rewriter：将依赖上下文的残缺问题改写为一个语义自包含、独立成立的问题。
 * 包私有：仅 QueryRouterServiceImpl 使用。
 */
@Service
public class QueryRewriter extends AbstractQueryComponent {

    /** 改写后的问题最大长度（超过则视为异常，降级） */
    private static final int MAX_REWRITE_LENGTH = 200;

    @Value("classpath:prompts/query/rewrite.st")
    private Resource rewriteTemplate;

    private String templateText;

    @Autowired
    @Qualifier("deepSeekChatModel")
    private ChatModel chatModel;

    @PostConstruct
    void init() {
        this.templateText = loadTemplate(rewriteTemplate);
    }

    /**
     * 重写问题
     *
     * @param currentQuery 当前问题
     * @param historyText  格式化后的历史文本
     * @return 改写后的自包含问题文本；调用失败或结果不合法时抛出异常由上层降级
     */
    public String rewrite(String currentQuery, String historyText) {
        String promptText = renderTemplate(templateText,
                "recentHistory", historyText == null ? "" : historyText,
                "currentQuery", currentQuery);

        String result = callChat(chatModel, promptText, 0.0);

        // 去掉可能残留的引号
        result = stripWrappingQuotes(result);

        if (result.isBlank()) {
            throw new IllegalStateException("Rewrite 返回空文本");
        }
        if (result.length() > MAX_REWRITE_LENGTH) {
            log.warn("[QueryRewriter] 改写结果过长 ({}>{})，降级使用原 query。result={}",
                    result.length(), MAX_REWRITE_LENGTH, result);
            throw new IllegalStateException("Rewrite 结果超过长度上限: " + result.length());
        }
        return result;
    }

    private String stripWrappingQuotes(String s) {
        if (s.length() >= 2
                && ((s.startsWith("\"") && s.endsWith("\""))
                || (s.startsWith("“") && s.endsWith("”"))
                || (s.startsWith("'") && s.endsWith("'")))) {
            return s.substring(1, s.length() - 1).trim();
        }
        return s;
    }
}
