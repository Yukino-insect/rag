
package com.jh.han.rag.core.parser.mineru;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * MinerU 异步任务提交响应
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MinerUTaskSubmitResponse(
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
            String docId
    ) {
    }
}
