package org.grayray.aiassistant.document.service;

/**
 * 文本清洗服务
 */
public interface TextCleanService {

    /**
     * 清洗原始文本，去除页眉、页脚、页码、多余空格、空行、重复标题等噪声
     *
     * @param rawText 原始文本
     * @return 清洗后的文本
     */
    String clean(String rawText);
}
