package org.grayray.aiassistant.document.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("knowledge_base")
@Schema(description = "知识库")
public class KnowledgeBase {

    @TableId(type = IdType.AUTO)
    @Schema(description = "知识库ID")
    private Long id;

    @Schema(description = "创建用户ID")
    private Long userId;

    @Schema(description = "知识库名称")
    private String name;

    @Schema(description = "知识库描述")
    private String description;

    @Schema(description = "向量库类型 SIMPLE/MILVUS/PGVECTOR")
    private String vectorStoreType;

    @Schema(description = "向量库持久化路径或目录")
    private String vectorStorePath;

    @Schema(description = "向量库集合/collection名称")
    private String vectorCollection;

    @Schema(description = "知识库状态 ACTIVE/INACTIVE/REBUILDING")
    private String status;

    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    @Schema(description = "更新时间")
    private LocalDateTime updateTime;

    @TableLogic
    @Schema(description = "是否删除 0-否 1-是")
    private Integer isDeleted;
}
