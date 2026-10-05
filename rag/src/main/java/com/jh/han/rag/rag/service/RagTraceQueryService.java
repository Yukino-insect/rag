
package com.jh.han.rag.rag.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.jh.han.rag.rag.controller.request.RagTraceRunPageRequest;
import com.jh.han.rag.rag.controller.vo.RagTraceDetailVO;
import com.jh.han.rag.rag.controller.vo.RagTraceNodeVO;
import com.jh.han.rag.rag.controller.vo.RagTraceRunVO;

import java.util.List;

/**
 * RAG Trace 查询服务
 */
public interface RagTraceQueryService {

    IPage<RagTraceRunVO> pageRuns(RagTraceRunPageRequest request);

    RagTraceDetailVO detail(String traceId);

    List<RagTraceNodeVO> listNodes(String traceId);
}
