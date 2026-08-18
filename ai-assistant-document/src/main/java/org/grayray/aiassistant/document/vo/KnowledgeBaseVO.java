package org.grayray.aiassistant.document.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "知识库信息")
public class KnowledgeBaseVO {

    private Long id;
    private Long userId;
    private String name;
    private String description;
    private String vectorStoreType;
    private String vectorStorePath;
    private String vectorCollection;
    private String status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
