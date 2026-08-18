package org.grayray.aiassistant.rag.groundedness;

import lombok.Data;

import java.util.List;

/**
 * Groundedness Check 判决结果
 * <p>
 * 由 LLM 作为裁判，判断 AI 回答的每条声明是否在参考文档中有支撑。
 */
@Data
public class GroundednessCheckResult {

    /**
     * 整体是否被参考文档支撑（true=支撑度足够，false=存在较多不支撑内容）
     */
    private Boolean supported;

    /**
     * 支撑度分数 0.0~1.0（被支撑的声明数 / 总声明数）
     */
    private Double score;

    /**
     * 未被参考文档支撑的句子列表
     */
    private List<UnsupportedSentence> unsupportedSentences;

    /**
     * 是否存在引用错误（如 [n] 指向的片段并不支撑该句、引用编号不存在等）
     */
    private Boolean hasCitationErrors;

    /**
     * 引用错误的具体描述
     */
    private List<String> citationIssues;

    /**
     * 判定理由（整段说明，便于日志）
     */
    private String reason;

    @Data
    public static class UnsupportedSentence {
        /** 未被支撑的原句文本 */
        private String sentence;
        /** 判定未支撑的理由（如：文档中无对应信息、与文档矛盾、纯属猜测） */
        private String reason;
    }

    /** 兜底构造：校验异常时返回 null，由调用方按 failOpen 处理 */
}