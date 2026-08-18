package org.grayray.aiassistant.rag.model;

import lombok.Data;

import java.util.List;

/**
 * Query Expansion 输出结果
 */
@Data
public class ExpansionResult {

    /** 拆解后的子查询列表（2~4 条） */
    private List<String> queries;

    /** 拆解理由 */
    private String reason;
}
