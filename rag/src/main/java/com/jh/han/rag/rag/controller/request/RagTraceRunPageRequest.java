
package com.jh.han.rag.rag.controller.request;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jh.han.rag.rag.dao.entity.RagTraceRunDO;
import lombok.Data;

/**
 * RAG Trace 运行记录分页请求
 */
@Data
public class RagTraceRunPageRequest extends Page<RagTraceRunDO> {

    private String traceId;

    private String conversationId;

    private String taskId;

    private String status;
}
