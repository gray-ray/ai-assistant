-- 创建数据库

create database  if not exists ai_assistant
       default  character set utf8mb4
       default  collate utf8mb4_unicode_ci;

-- 创建系统用户表

create table if not exists sys_user (
    id bigint primary key  auto_increment comment '用户id',
    user_name varchar(50) not null comment '用户名',
    create_time datetime default current_timestamp comment '创建时间',
    update_time datetime default current_timestamp on update current_timestamp comment '更新时间',
    is_deleted tinyint not null  default 0 comment  '是否删除 0- 否 ,1-是'
)
comment ='系统用户表';

-- 创建会话表

create table if not exists chat_session(
    id bigint primary key  auto_increment comment 'id',
    user_id bigint not null comment  '用户id',
    session_id varchar(64) not null comment '业务会话id',
    title varchar(255) comment '会话标题',
    session_type VARCHAR(50) COMMENT '会话类型',
    model_name VARCHAR(50) COMMENT '模型名称',
    knowledge_id BIGINT DEFAULT NULL COMMENT '绑定的知识库ID，RAG对话使用',
    summary TEXT COMMENT '上下文摘要',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    is_deleted tinyint not null  default 0 comment  '是否删除 0- 否 ,1-是',
    unique key uk_session_id(session_id),
    index idx_user_id(user_id),
    index idx_knowledge_id(knowledge_id),
    index idx_user_knowledge(user_id, knowledge_id)

)
comment = '会话表';

-- 创建消息表

create table if not exists chat_message(
    id bigint primary key auto_increment,
    session_id bigint not null comment '会话id',
    user_id bigint not null comment '用户id',
    role varchar(20) not null  comment 'user/assistant/system',
    content longtext not null comment '消息内容',
    citations_json JSON NULL COMMENT '引用来源列表 JSON（仅 assistant 消息有值）',
    message_index int not null  comment '消息顺序',

    model_name VARCHAR(50) COMMENT '模型名称',

    finish_reason varchar(50) comment '结束原因',


    create_time datetime default current_timestamp comment '创建时间',
    is_deleted tinyint not null  default 0 comment  '是否删除 0- 否 ,1-是',

    index idx_session_id(session_id),
    index idx_session_message(session_id, message_index),
    index idx_user_id(user_id)

) comment='对话消息';

-- 知识库表
create table if not exists knowledge_base(
    id bigint primary key auto_increment comment '知识库ID',
    user_id bigint not null comment '创建用户ID',
    name varchar(100) not null comment '知识库名称',
    description varchar(500) default null comment '知识库描述',
    vector_store_type varchar(50) not null default 'SIMPLE' comment '向量库类型 SIMPLE/MILVUS/PGVECTOR 等',
    vector_store_path varchar(1000) default null comment '向量库持久化路径或目录，SimpleVectorStore使用',
    vector_collection varchar(200) default null comment '向量库集合/collection名称，Milvus/PGVector使用',
    status varchar(20) not null default 'ACTIVE' comment '知识库状态 ACTIVE/INACTIVE/REBUILDING',
    create_time datetime default current_timestamp comment '创建时间',
    update_time datetime default current_timestamp on update current_timestamp comment '更新时间',
    is_deleted tinyint not null default 0 comment '是否删除 0-否, 1-是',
    index idx_user_id(user_id),
    index idx_user_status(user_id, status),
    index idx_status(status),
    index idx_vector_store_type(vector_store_type)
) comment = '知识库表';

-- 上传文件信息列表
create table if not exists document_info(
    id bigint primary key auto_increment,
    knowledge_id bigint default null comment '知识库ID，关联knowledge_base.id',
    file_url varchar(1000) not null comment '文件路径',
    file_name varchar(255) not null comment '文件名称',
    origin_file_name varchar(255) not null comment '初始文件名称',
    file_type varchar(50)  comment '文件类型 default pdf',
    file_size bigint comment '文件大小',
    storage_type varchar(50) comment '存储类型',
    storage_path varchar(1000) comment '存储路径',


    session_id bigint  comment '会话id',
    user_id bigint  comment '用户id',
    message_id  bigint  comment '消息id',

    process_status varchar(20) default 'pending' comment '处理状态 pending/processing/completed/failed',
    process_error varchar(1000) comment '处理失败错误信息',

    create_time datetime default CURRENT_TIMESTAMP,

    is_deleted tinyint not null  default 0 comment  '是否删除 0- 否 ,1-是',

    index idx_file_name(file_name),
    index idx_knowledge_id(knowledge_id),
    index idx_user_knowledge(user_id, knowledge_id),
    index idx_process_status(process_status)
#   后续加入 session 、user 、message 索引
) comment ='文件信息';

-- 文档Chunk表
create table if not exists document_chunk(
    id bigint primary key auto_increment comment 'Chunk数据库ID',
    chunk_id varchar(200) not null comment 'Chunk业务ID，如doc_1_chunk_0或doc_1_v2_chunk_0',
    document_id bigint not null comment '文档ID，对应document_info.id',
    knowledge_id bigint not null comment '知识库ID，对应knowledge_base.id',
    chunk_version int not null default 1 comment '切分版本，同一文档重新切分时递增',
    chunk_index int not null comment 'Chunk顺序，从0开始',
    total_chunks int default null comment '文档总Chunk数',
    content text not null comment 'Chunk文本内容',
    content_hash varchar(64) default null comment 'Chunk内容SHA-256，用于去重/校验',
    page_number int default null comment 'Chunk所在PDF页码',
    chapter_index int default null comment '章节序号',
    chapter_title varchar(500) default null comment '章节标题',
    token_count int default null comment 'Chunk Token数量',
    vector_id varchar(200) default null comment '向量库中的向量ID或业务主键',
    metadata_json json default null comment '扩展元数据，如页码范围、坐标、解析器信息',
    create_time datetime default current_timestamp comment '创建时间',
    update_time datetime default current_timestamp on update current_timestamp comment '更新时间',
    is_deleted tinyint not null default 0 comment '是否删除 0-否, 1-是',
    unique key uk_chunk_id(chunk_id),
    unique key uk_doc_version_chunk(document_id, chunk_version, chunk_index),
    index idx_document_id(document_id),
    index idx_knowledge_id(knowledge_id),
    index idx_knowledge_doc(knowledge_id, document_id),
    index idx_document_chunk(document_id, chunk_version, chunk_index),
    index idx_vector_id(vector_id)
) comment = '文档Chunk表';
