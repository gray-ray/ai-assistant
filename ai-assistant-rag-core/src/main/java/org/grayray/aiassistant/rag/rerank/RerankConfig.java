package org.grayray.aiassistant.rag.rerank;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.grayray.aiassistant.rag.rerank.RerankProperties;
import org.grayray.aiassistant.rag.rerank.client.BgeRerankerClient;
import org.grayray.aiassistant.rag.rerank.impl.BgeRerankServiceImpl;
import org.grayray.aiassistant.rag.rerank.impl.NoopRerankServiceImpl;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Rerank 模块配置
 * <p>
 * 根据 {@code ai.rerank.enabled} 开关决定注入哪个 {@link RerankService} 实现：
 * <ul>
 *   <li>{@code enabled=true} → {@link BgeRerankServiceImpl}（调用 bge-reranker-v2-m3 服务）</li>
 *   <li>{@code enabled=false} → {@link NoopRerankServiceImpl}（透传，零开销）</li>
 * </ul>
 * 同时配置 reranker HTTP 客户端的 {@link RestClient}。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class RerankConfig {

    private final RerankProperties properties;

    /**
     * 配置 bge reranker 服务的 RestClient
     */
    @Bean(name = "bgeRerankerRestClient")
    @ConditionalOnProperty(prefix = "ai.rerank", name = "enabled", havingValue = "true")
    public RestClient bgeRerankerRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) properties.getTimeoutMs());
        factory.setReadTimeout((int) properties.getTimeoutMs());

        return RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(factory)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    /**
     * 配置 BgeRerankerClient
     */
    @Bean
    @ConditionalOnProperty(prefix = "ai.rerank", name = "enabled", havingValue = "true")
    public BgeRerankerClient bgeRerankerClient(
            @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection") RestClient bgeRerankerRestClient) {
        return new BgeRerankerClient(bgeRerankerRestClient);
    }

    /**
     * 启用时注入 BgeRerankServiceImpl 作为主 RerankService（@Primary）
     */
    @Bean
    @Primary
    @ConditionalOnProperty(prefix = "ai.rerank", name = "enabled", havingValue = "true")
    public RerankService bgeRerankService(BgeRerankerClient bgeRerankerClient) {
        log.info("");
        log.info("============================================================");
        log.info("🔄 RerankService（bge-reranker-v2-m3）已启用");
        log.info("   服务地址    : {}", properties.getBaseUrl());
        log.info("   模型        : {}", properties.getModel());
        log.info("   topM        : {}", properties.getTopM());
        log.info("   minScore    : {}", properties.getMinScore());
        log.info("   batchSize   : {}", properties.getBatchSize());
        log.info("   timeout     : {}ms", properties.getTimeoutMs());
        log.info("   alwaysRerank: {}", properties.isAlwaysRerank());
        log.info("   分数融合    : {}", properties.getFusion() != null && properties.getFusion().isEnabled()
                ? "enabled (alpha=" + properties.getFusion().getAlpha() + ")" : "disabled");
        log.info("============================================================");
        log.info("");
        return new BgeRerankServiceImpl(properties, bgeRerankerClient);
    }

    /**
     * 关闭时注入 NoopRerankServiceImpl 作为主 RerankService
     */
    @Bean
    @Primary
    @ConditionalOnProperty(prefix = "ai.rerank", name = "enabled", havingValue = "false", matchIfMissing = true)
    public RerankService noopRerankService() {
        log.debug("[RerankConfig] Rerank 开关关闭，使用 NoopRerankService（透传）");
        return new NoopRerankServiceImpl();
    }

    @PostConstruct
    public void logConfig() {
        if (!properties.isEnabled()) {
            log.info("[RerankConfig] Rerank 模块未启用（ai.rerank.enabled=false）");
        }
    }
}
