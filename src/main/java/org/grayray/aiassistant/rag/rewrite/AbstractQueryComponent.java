package org.grayray.aiassistant.rag.rewrite;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * LLM 调用基础支持类，供 Classifier / Rewriter / Expander 复用。
 * 包私有：仅 QueryRouterServiceImpl 及其同包组件使用。
 */
abstract public class AbstractQueryComponent {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    protected static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 从 Resource 加载 Prompt 模板文本
     */
    protected String loadTemplate(Resource resource) {
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("加载 Prompt 模板失败: " + resource, e);
        }
    }

    /**
     * 简单占位符替换：{key} → value
     */
    protected String renderTemplate(String template, String... keyValues) {
        String result = template;
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            result = result.replace("{" + keyValues[i] + "}", keyValues[i + 1] == null ? "" : keyValues[i + 1]);
        }
        return result;
    }

    /**
     * 调用 ChatModel，返回文本结果
     */
    protected String callChat(ChatModel chatModel, String promptText, Double temperature) {
        DeepSeekChatOptions options = DeepSeekChatOptions.builder()
                .temperature(temperature)
                .build();
        Prompt prompt = new Prompt(List.of(new UserMessage(promptText)), options);
        ChatResponse response = chatModel.call(prompt);
        if (response == null || response.getResult() == null
                || response.getResult().getOutput() == null
                || response.getResult().getOutput().getText() == null) {
            throw new IllegalStateException("LLM 返回为空");
        }
        return response.getResult().getOutput().getText().trim();
    }

    /**
     * 从文本中提取 JSON 对象（兼容 Markdown 代码块包裹的情况）
     */
    protected String extractJson(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        // 去掉 ```json ... ``` 包裹
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastBackticks = trimmed.lastIndexOf("```");
            if (firstNewline > 0 && lastBackticks > firstNewline) {
                trimmed = trimmed.substring(firstNewline + 1, lastBackticks).trim();
            }
        }
        // 找到第一个 { 和最后一个 }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }

    /**
     * 解析 JSON 为指定类型
     */
    protected <T> T parseJson(String jsonText, Class<T> clazz) throws JsonProcessingException {
        return MAPPER.readValue(jsonText, clazz);
    }

    /**
     * 解析 JSON 为 JsonNode
     */
    protected JsonNode parseJsonNode(String jsonText) throws JsonProcessingException {
        return MAPPER.readTree(jsonText);
    }
}
