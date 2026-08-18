package org.grayray.aiassistant.document.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "文件上传结果")
public class DocumentUploadResult {

    @Schema(description = "文档ID")
    private Long documentId;

    @Schema(description = "存储文件名")
    private String fileName;

    @Schema(description = "文件大小（字节）")
    private Long fileSize;

    @Schema(description = "访问URL")
    private String fileUrl;

    @Schema(description = "处理状态 pending/processing/completed/failed")
    private String processStatus;

}
