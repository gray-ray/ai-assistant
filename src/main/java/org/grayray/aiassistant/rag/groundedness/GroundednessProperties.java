package org.grayray.aiassistant.rag.groundedness;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 回答事实校验（Groundedness Check）配置项
 * <p>
 * 通过 {@code application.yaml} 中的 {@code ai.rag.groundedness} 前缀注入，
 * 控制是否在 RAG 回答生成后进行事实支撑性校验、阈值、失败策略等。
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.rag.groundedness")
public class GroundednessProperties {

    /**
     * 总开关。false 时完全跳过校验步骤（默认 true 开启）
     */
    private boolean enabled = true;

    /**
     * 支撑度阈值（0~1）。整体支撑度低于此值视为"严重不支撑"，会在回答前追加警示
     */
    private double threshold = 0.7;

    /**
     * 是否在未被参考文档支撑的句子末尾追加 ⚠️ 标记
     */
    private boolean markUnsupported = true;

    /**
     * fail-open：校验异常时是否放行原回答（true=放行，false=返回"校验失败"提示）
     */
    private boolean failOpen = true;

    /**
     * 校验调用超时（毫秒）
     */
    private long timeoutMs = 10_000;
}