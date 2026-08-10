package org.grayray.aiassistant.document.entity;

import com.baomidou.mybatisplus.annotation.*;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@TableName("document_info")
@Data
@Schema(description = "上传文件信息")
public class DocumentInfo {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String fileUrl;
    private String fileName;
    private String fileType;
    private Long fileSize;
    private String originFileName;
    private String storageType;

    private String storagePath;

    private Long sessionId;

    private Long userId;
    private Long messageId;

    @Schema(description = "处理状态 pending/processing/completed/failed")
    private String processStatus;

    @Schema(description = "处理失败错误信息")
    private String processError;

    @TableField(fill = FieldFill.INSERT)

    private LocalDateTime createTime;

    @TableLogic
    @Schema(description = "是否删除 0-否 1-是")
    private Integer isDeleted;





}
