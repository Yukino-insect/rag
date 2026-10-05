
package com.jh.han.rag.knowledge.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * MinerU 异步解析任务状态
 */
@Getter
@RequiredArgsConstructor
public enum KnowledgeDocumentMineruTaskStatus {

    /**
     * 已提交给 Python 服务，等待回调
     */
    SUBMITTED("submitted"),

    /**
     * python 解析成功
     */
    SUCCESS("success"),
    /**
     * python 解析失败
     */

    /**
     * python 任务正在执行中
     */
    RUNNING("running"),

    FAILED("failed"),

    /**
     * 已收到成功回调，等待 Java 侧拉取结果并续跑
     */
    CALLBACK_RECEIVED("callback_received"),;

    private final String code;

    public static KnowledgeDocumentMineruTaskStatus from(String status) {
        return switch (status) {
            case "submitted" -> SUBMITTED;
            case "success" -> SUCCESS;
            case "running" -> RUNNING;
            case "failed" -> FAILED;
            case "callback_received" -> CALLBACK_RECEIVED;
            default -> throw new IllegalStateException("Unexpected value: " + status);
        };
    }
}
