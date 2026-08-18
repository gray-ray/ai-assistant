package org.grayray.aiassistant.rag.groundedness;

/**
 * 回答事实校验契约。
 */
public interface GroundednessChecker {

    GroundednessCheckResult check(String contextText, String answer);
}
