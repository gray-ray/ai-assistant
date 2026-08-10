-- ============================================================================
-- v2 增量迁移：消息表增加引用来源字段
-- 适用：已存在 v1 版本数据库的环境
-- 全新环境直接使用 schema.sql 即可（已包含 citations_json 字段）
-- ============================================================================

USE ai_assistant;

-- 消息表增加 citations_json 字段（JSON 类型，存储引用来源列表）
-- 仅 assistant 角色的消息会有值，user/system 消息为 NULL
ALTER TABLE chat_message
    ADD COLUMN citations_json JSON NULL COMMENT '引用来源列表 JSON（仅 assistant 消息有值）'
    AFTER content;
