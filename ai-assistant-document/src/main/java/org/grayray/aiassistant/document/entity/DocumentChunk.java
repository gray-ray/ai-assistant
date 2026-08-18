package org.grayray.aiassistant.document.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@TableName(value = "document_chunk", autoResultMap = true)
@Schema(description = "文档Chunk")
public class DocumentChunk {

    @TableId(type = IdType.AUTO)
    @Schema(description = "Chunk数据库ID")
    private Long id;

    @Schema(description = "Chunk业务ID")
    private String chunkId;

    @Schema(description = "文档ID")
    private Long documentId;

    @Schema(description = "知识库ID")
    private Long knowledgeId;

    @Schema(description = "切分版本")
    private Integer chunkVersion;

    @Schema(description = "Chunk顺序，从0开始")
    private Integer chunkIndex;

    @Schema(description = "文档总Chunk数")
    private Integer totalChunks;

    @Schema(description = "Chunk文本内容")
    private String content;

    @Schema(description = "Chunk内容SHA-256")
    private String contentHash;

    @Schema(description = "Chunk所在PDF页码")
    private Integer pageNumber;

    @Schema(description = "章节序号")
    private Integer chapterIndex;

    @Schema(description = "章节标题")
    private String chapterTitle;

    @Schema(description = "Chunk Token数量")
    private Integer tokenCount;

    @Schema(description = "向量库中的向量ID或业务主键")
    private String vectorId;

    @TableField(value = "metadata_json", typeHandler = JacksonTypeHandler.class)
    @Schema(description = "扩展元数据")
    private Map<String, Object> metadata;

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
