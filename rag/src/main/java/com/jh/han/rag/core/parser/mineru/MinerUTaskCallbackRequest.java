
package com.jh.han.rag.core.parser.mineru;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Python 服务回调 Java 的任务通知体
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MinerUTaskCallbackRequest(
        @JsonProperty("task_id")
        String taskId,
        String status,
        @JsonProperty("doc_id")
        String docId,
        @JsonProperty("request_id")
        String requestId,
        @JsonProperty("duration_ms")
        Long durationMs,
        String error
) {
}
