
package com.jh.han.rag.rag.dto;

import com.jh.han.rag.rag.core.intent.NodeScore;

/**
 * 意图候选及其子问题索引
 *
 * @param subQuestionIndex 子问题下标
 * @param nodeScore        意图候选分数
 */
public record IntentCandidate(int subQuestionIndex, NodeScore nodeScore) {
}
