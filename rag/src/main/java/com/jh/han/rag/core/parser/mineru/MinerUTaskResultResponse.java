
package com.jh.han.rag.core.parser.mineru;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * MinerU 异步任务结果响应
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MinerUTaskResultResponse(
        boolean success,
        Data data,
        String error
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Data(
            @JsonProperty("task_id")
            String taskId,
            String status,
            @JsonProperty("doc_id")
            String docId,
            @JsonProperty("request_id")
            String requestId,
            @JsonProperty("duration_ms")
            Long durationMs,
            String error,
            MinerULikeServiceResponse.Data result
    ) {
    }
}
