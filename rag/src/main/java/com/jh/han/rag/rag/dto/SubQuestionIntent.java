
package com.jh.han.rag.rag.dto;

import java.util.List;

import com.jh.han.rag.rag.core.intent.NodeScore;

/**
 * 子问题与其意图候选
 *
 * @param subQuestion 子问题文本
 * @param nodeScores  子问题的意图候选
 */
public record SubQuestionIntent(String subQuestion, List<NodeScore> nodeScores) {
}
