// ======================================================================
// Milvus 配置（已注释，改用 Spring AI SimpleVectorStore）
// ----------------------------------------------------------------------
// 如需重新启用 Milvus：
//   1. 取消 pom.xml 中 milvus-sdk-java 依赖的注释
//   2. 取消本文件类上的 @Configuration 等注解的注释
//   3. 取消 application.yaml 中 milvus 配置块的注释
//   4. 取消 MilvusVectorStoreService 类上的 @Service/@Primary 注解的注释
//   5. 确保 Milvus 服务已启动（默认 localhost:19530）
// ======================================================================
/*
package org.grayray.aiassistant.config.vector;

import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Milvus 客户端配置
 * <p>
 * 通过 {@code milvus.host/port} 连接 Milvus 服务，注册 {@link MilvusClientV2} Bean。
 * 当 {@code milvus.enabled=false}（未配置时默认 true）时不创建 Bean。
 *
@Slf4j
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(name = "milvus.enabled", havingValue = "true", matchIfMissing = true)
public class MilvusConfig {

    private final MilvusProperties milvusProperties;

    @Bean
    public MilvusClientV2 milvusClientV2() {
        String endpoint = String.format("http://%s:%d",
                milvusProperties.getHost(), milvusProperties.getPort());

        ConnectConfig.ConnectConfigBuilder builder = ConnectConfig.builder()
                .uri(endpoint);

        if (milvusProperties.getToken() != null && !milvusProperties.getToken().isBlank()) {
            builder.token(milvusProperties.getToken());
        }
        if (milvusProperties.getDatabase() != null && !milvusProperties.getDatabase().isBlank()) {
            builder.dbName(milvusProperties.getDatabase());
        }

        MilvusClientV2 client = new MilvusClientV2(builder.build());
        log.info("Milvus 客户端初始化成功, endpoint={}, database={}, collection={}, dim={}",
                endpoint, milvusProperties.getDatabase(),
                milvusProperties.getCollectionName(), milvusProperties.getDimension());
        return client;
    }
}
*/
