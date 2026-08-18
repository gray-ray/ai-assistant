package org.grayray.aiassistant.chat.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

/**
 * 引用来源 VO
 * <p>
 * 对应回答中的 [n] 引用标记，供前端展示来源文档、章节以及片段内容。
 */
@Data
@Builder
@Schema(description = "引用来源")
public class CitationVO {

    @Schema(description = "引用编号（对应回答中的 [n]）")
    private Integer index;

    @Schema(description = "文档ID")
    private Long documentId;

    @Schema(description = "文档名称")
    private String documentName;

    @Schema(description = "章节标题")
    private String chapterTitle;

    @Schema(description = "片段内容（完整，用于悬浮展示）")
    private String content;

    @Schema(description = "相关性分数")
    private Double score;
}
