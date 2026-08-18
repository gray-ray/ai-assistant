package org.grayray.aiassistant.document.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "创建知识库请求")
public class KnowledgeBaseCreateDTO {

    @NotNull(message = "用户ID不能为空")
    @Schema(description = "用户ID")
    private Long userId;

    @NotBlank(message = "知识库名称不能为空")
    @Size(max = 100, message = "知识库名称不能超过100字")
    @Schema(description = "知识库名称")
    private String name;

    @Size(max = 500, message = "知识库描述不能超过500字")
    @Schema(description = "知识库描述")
    private String description;
}
