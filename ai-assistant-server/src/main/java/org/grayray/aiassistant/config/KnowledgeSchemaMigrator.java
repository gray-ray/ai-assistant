package org.grayray.aiassistant.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;

@Component
@ConditionalOnProperty(name = "ai.schema-migration.enabled", havingValue = "true", matchIfMissing = true)
public class KnowledgeSchemaMigrator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSchemaMigrator.class);

    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;

    public KnowledgeSchemaMigrator(JdbcTemplate jdbcTemplate, DataSource dataSource) {
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        ensureKnowledgeBaseTable();
        ensureDocumentChunkTable();
        ensureColumn("document_info", "knowledge_id",
                "ALTER TABLE document_info ADD COLUMN knowledge_id BIGINT DEFAULT NULL COMMENT '知识库ID，关联knowledge_base.id' AFTER id");
        ensureColumn("chat_session", "knowledge_id",
                "ALTER TABLE chat_session ADD COLUMN knowledge_id BIGINT DEFAULT NULL COMMENT '绑定的知识库ID，RAG对话使用' AFTER model_name");
        ensureIndex("document_info", "idx_knowledge_id",
                "ALTER TABLE document_info ADD INDEX idx_knowledge_id(knowledge_id)");
        ensureIndex("document_info", "idx_user_knowledge",
                "ALTER TABLE document_info ADD INDEX idx_user_knowledge(user_id, knowledge_id)");
        ensureIndex("document_info", "idx_process_status",
                "ALTER TABLE document_info ADD INDEX idx_process_status(process_status)");
        ensureIndex("chat_session", "idx_knowledge_id",
                "ALTER TABLE chat_session ADD INDEX idx_knowledge_id(knowledge_id)");
        ensureIndex("chat_session", "idx_user_knowledge",
                "ALTER TABLE chat_session ADD INDEX idx_user_knowledge(user_id, knowledge_id)");
    }

    private void ensureKnowledgeBaseTable() throws SQLException {
        if (tableExists("knowledge_base")) {
            return;
        }
        jdbcTemplate.execute("""
                CREATE TABLE knowledge_base (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '知识库ID',
                    user_id BIGINT NOT NULL COMMENT '创建用户ID',
                    name VARCHAR(100) NOT NULL COMMENT '知识库名称',
                    description VARCHAR(500) DEFAULT NULL COMMENT '知识库描述',
                    vector_store_type VARCHAR(50) NOT NULL DEFAULT 'SIMPLE' COMMENT '向量库类型 SIMPLE/MILVUS/PGVECTOR 等',
                    vector_store_path VARCHAR(1000) DEFAULT NULL COMMENT '向量库持久化路径或目录，SimpleVectorStore使用',
                    vector_collection VARCHAR(200) DEFAULT NULL COMMENT '向量库集合/collection名称，Milvus/PGVector使用',
                    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT '知识库状态 ACTIVE/INACTIVE/REBUILDING',
                    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                    is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '是否删除 0-否, 1-是',
                    INDEX idx_user_id(user_id),
                    INDEX idx_user_status(user_id, status),
                    INDEX idx_status(status),
                    INDEX idx_vector_store_type(vector_store_type)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库表'
                """);
        log.info("[SchemaMigration] created table knowledge_base");
    }

    private void ensureDocumentChunkTable() throws SQLException {
        if (tableExists("document_chunk")) {
            return;
        }
        jdbcTemplate.execute("""
                CREATE TABLE document_chunk (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'Chunk数据库ID',
                    chunk_id VARCHAR(200) NOT NULL COMMENT 'Chunk业务ID，如doc_1_chunk_0或doc_1_v2_chunk_0',
                    document_id BIGINT NOT NULL COMMENT '文档ID，对应document_info.id',
                    knowledge_id BIGINT NOT NULL COMMENT '知识库ID，对应knowledge_base.id',
                    chunk_version INT NOT NULL DEFAULT 1 COMMENT '切分版本，同一文档重新切分时递增',
                    chunk_index INT NOT NULL COMMENT 'Chunk顺序，从0开始',
                    total_chunks INT DEFAULT NULL COMMENT '文档总Chunk数',
                    content TEXT NOT NULL COMMENT 'Chunk文本内容',
                    content_hash VARCHAR(64) DEFAULT NULL COMMENT 'Chunk内容SHA-256，用于去重/校验',
                    page_number INT DEFAULT NULL COMMENT 'Chunk所在PDF页码',
                    chapter_index INT DEFAULT NULL COMMENT '章节序号',
                    chapter_title VARCHAR(500) DEFAULT NULL COMMENT '章节标题',
                    token_count INT DEFAULT NULL COMMENT 'Chunk Token数量',
                    vector_id VARCHAR(200) DEFAULT NULL COMMENT '向量库中的向量ID或业务主键',
                    metadata_json JSON DEFAULT NULL COMMENT '扩展元数据，如页码范围、坐标、解析器信息',
                    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                    is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '是否删除 0-否, 1-是',
                    UNIQUE KEY uk_chunk_id(chunk_id),
                    UNIQUE KEY uk_doc_version_chunk(document_id, chunk_version, chunk_index),
                    INDEX idx_document_id(document_id),
                    INDEX idx_knowledge_id(knowledge_id),
                    INDEX idx_knowledge_doc(knowledge_id, document_id),
                    INDEX idx_document_chunk(document_id, chunk_version, chunk_index),
                    INDEX idx_vector_id(vector_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文档Chunk表'
                """);
        log.info("[SchemaMigration] created table document_chunk");
    }

    private void ensureColumn(String tableName, String columnName, String ddl) throws SQLException {
        if (columnExists(tableName, columnName)) {
            return;
        }
        jdbcTemplate.execute(ddl);
        log.info("[SchemaMigration] added column {}.{}", tableName, columnName);
    }

    private void ensureIndex(String tableName, String indexName, String ddl) throws SQLException {
        if (indexExists(tableName, indexName)) {
            return;
        }
        jdbcTemplate.execute(ddl);
        log.info("[SchemaMigration] added index {}.{}", tableName, indexName);
    }

    private boolean tableExists(String tableName) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            String schema = connection.getCatalog();
            try (ResultSet rs = metaData.getTables(schema, null, tableName, new String[]{"TABLE"})) {
                return rs.next();
            }
        }
    }

    private boolean columnExists(String tableName, String columnName) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            String schema = connection.getCatalog();
            try (ResultSet rs = metaData.getColumns(schema, null, tableName, columnName)) {
                return rs.next();
            }
        }
    }

    private boolean indexExists(String tableName, String indexName) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            String schema = connection.getCatalog();
            try (ResultSet rs = metaData.getIndexInfo(schema, null, tableName, false, false)) {
                while (rs.next()) {
                    if (indexName.equalsIgnoreCase(rs.getString("INDEX_NAME"))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
