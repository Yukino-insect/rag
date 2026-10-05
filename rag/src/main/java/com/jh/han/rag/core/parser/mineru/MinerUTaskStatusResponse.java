
package com.jh.han.rag.core.parser.mineru;

public record MinerUTaskStatusResponse(
       boolean success,
       Data data,
       String error
) {

    public record Data(
            String taskId,
            String status,
            String docId
    ) {
    }
}
