package org.grayray.aiassistant.rag.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Query Rewrite 输出结果
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RewriteResult {

    /** 改写后的自包含问题 */
    private String rewrittenQuery;
}
