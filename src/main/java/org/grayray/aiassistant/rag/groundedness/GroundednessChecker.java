package org.grayray.aiassistant.rag.groundedness;

import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.annotation.PostConstruct;
import org.grayray.aiassistant.rag.rewrite.AbstractQueryComponent;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

/**
 * 回答事实校验服务（Groundedness / Faithfulness Checker）
 * <p>
 * 在 RAG 回答生成后调用 LLM 作为裁判，判断回答中的每条事实性声明
 * 是否都被参考文档直接支撑，返回结构化判决结果。
 * <p>
 * 遵循项目现有 AbstractQueryComponent 模式：加载 .st 模板、
 * temperature=0.0 保证确定性、JSON 解析、失败时重试一次。
 */
@Service
public class GroundednessChecker extends AbstractQueryComponent {

    // log 由父类 AbstractQueryComponent 提供（LoggerFactory.getLogger(getClass())）

    /** 校验调用使用的 temperature —— 0.0 保证判决确定性 */
    private static final double CHECK_TEMPERATURE = 0.0;

    /** 最大重试次数（首次 + 一次重试） */
    private static final int MAX_ATTEMPTS = 2;

    @Value("classpath:prompts/rag/groundedness-check.st")
    private Resource templateResource;

    @Autowired
    @Qualifier("deepSeekChatModel")
    private ChatModel chatModel;

    private String templateText;

    @PostConstruct
    void init() {
        this.templateText = loadTemplate(templateResource);
        log.info("[GroundednessChecker] 模板加载完成: groundedness-check={}字", templateText.length());
    }

    /**
     * 对 AI 回答进行事实支撑性校验
     *
     * @param contextText 参考文档的拼接文本（即 AssembledContext.getText()）
     * @param answer      AI 生成的回答（待校验）
     * @return 结构化判决结果；调用失败时抛异常由调用方按 failOpen 策略处理
     */
    public GroundednessCheckResult check(String contextText, String answer) {
        if (contextText == null || contextText.isBlank()) {
            // 无上下文时直接判定为"完全支撑"—— 该模式下由降级提示告知用户非文档回答
            return fullySupported("无参考文档，跳过校验");
        }
        if (answer == null || answer.isBlank()) {
            return fullySupported("回答为空，跳过校验");
        }

        String prompt = renderTemplate(templateText,
                "context", contextText,
                "answer", answer);

        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                String raw = callChat(chatModel, prompt, CHECK_TEMPERATURE);
                String json = extractJson(raw);
                GroundednessCheckResult result = parseJson(json, GroundednessCheckResult.class);
                // 基本字段补全
                if (result.getSupported() == null) {
                    result.setSupported(result.getScore() != null && result.getScore() >= 0.9);
                }
                if (result.getScore() == null) {
                    result.setScore(result.getSupported() ? 1.0 : 0.0);
                }
                log.debug("[GroundednessChecker] 校验完成: supported={}, score={}, unsupported={}, citeErrs={}",
                        result.getSupported(), result.getScore(),
                        result.getUnsupportedSentences() == null ? 0 : result.getUnsupportedSentences().size(),
                        Boolean.TRUE.equals(result.getHasCitationErrors()));
                return result;
            } catch (JsonProcessingException e) {
                lastError = e;
                log.warn("[GroundednessChecker] JSON 解析失败(第{}次): {}", attempt, e.getMessage());
            } catch (Exception e) {
                lastError = e;
                log.warn("[GroundednessChecker] 调用失败(第{}次): {}", attempt, e.getMessage());
                break; // 网络/模型异常直接跳出（非解析错误，重试意义不大）
            }
        }
        throw new IllegalStateException("Groundedness 校验失败: "
                + (lastError == null ? "未知错误" : lastError.getMessage()), lastError);
    }

    private GroundednessCheckResult fullySupported(String reason) {
        GroundednessCheckResult r = new GroundednessCheckResult();
        r.setSupported(true);
        r.setScore(1.0);
        r.setUnsupportedSentences(java.util.Collections.emptyList());
        r.setHasCitationErrors(false);
        r.setCitationIssues(java.util.Collections.emptyList());
        r.setReason(reason);
        return r;
    }
}