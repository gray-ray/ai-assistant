// ======================================================================
// Milvus 配置属性类（已注释，改用 Spring AI SimpleVectorStore）
// ----------------------------------------------------------------------
// 如需重新启用 Milvus，请取消下方类注释和注解注释。
// ======================================================================
/*
package org.grayray.aiassistant.config.vector;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Milvus 向量数据库配置项
 *
@Data
@Component
@ConfigurationProperties(prefix = "milvus")
public class MilvusProperties {

    /** 是否启用 Milvus 向量存储（关闭则回退到内存存储） *
    private boolean enabled = true;

    /** Milvus 服务地址 *
    private String host = "localhost";

    /** Milvus gRPC 端口（默认 19530） *
    private int port = 19530;

    /** 鉴权 Token（格式：username:password，云版或开启鉴权时使用） *
    private String token;

    /** 数据库名（Milvus 2.3+ 支持多数据库，默认 default） *
    private String database = "default";

    /** Collection 名称 *
    private String collectionName = "doc_chunks";

    /** 向量维度（bge-m3=1024；nomic-embed-text=768） *
    private int dimension = 1024;

    /** 索引类型 *
    private String indexType = "AUTOINDEX";

    /** 相似度度量方式：COSINE / IP / L2 *
    private String metricType = "COSINE";

    /** 启动时自动创建 collection 与索引 *
    private boolean autoCreateCollection = true;
}
*/
