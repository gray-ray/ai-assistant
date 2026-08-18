package org.grayray.aiassistant.document.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "更新知识库请求")
public class KnowledgeBaseUpdateDTO {

    @NotNull(message = "用户ID不能为空")
    @Schema(description = "用户ID")
    private Long userId;

    @Size(max = 100, message = "知识库名称不能超过100字")
    @Schema(description = "知识库名称")
    private String name;

    @Size(max = 500, message = "知识库描述不能超过500字")
    @Schema(description = "知识库描述")
    private String description;

    @Schema(description = "知识库状态 ACTIVE/INACTIVE")
    private String status;
}
