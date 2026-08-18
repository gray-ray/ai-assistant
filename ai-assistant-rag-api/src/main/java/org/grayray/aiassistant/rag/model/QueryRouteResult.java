package org.grayray.aiassistant.rag.model;

import lombok.Data;

/**
 * 分类器输出结果
 */
@Data
public class QueryRouteResult {

    /** 问题类型：simple / contextual / complex */
    private String type;

    /** 分类理由 */
    private String reason;
}
