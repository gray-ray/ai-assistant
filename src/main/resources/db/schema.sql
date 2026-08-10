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
    summary TEXT COMMENT '上下文摘要',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    is_deleted tinyint not null  default 0 comment  '是否删除 0- 否 ,1-是',
    unique key uk_session_id(session_id),
    index idx_user_id(user_id)

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


-- 上传文件信息列表
create table if not exists document_info(
    id bigint primary key auto_increment,
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

    index idx_file_name(file_name)
#   后续加入 session 、user 、message 索引
) comment ='文件信息';
