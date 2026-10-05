
package com.jh.han.rag.rag.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.jh.han.rag.framework.convention.Result;
import com.jh.han.rag.framework.web.Results;
import com.jh.han.rag.rag.controller.request.RagTraceRunPageRequest;
import com.jh.han.rag.rag.controller.vo.RagTraceDetailVO;
import com.jh.han.rag.rag.controller.vo.RagTraceNodeVO;
import com.jh.han.rag.rag.controller.vo.RagTraceRunVO;
import com.jh.han.rag.rag.service.RagTraceQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * RAG Trace 查询接口
 */
@RestController
@RequiredArgsConstructor
public class RagTraceController {

    private final RagTraceQueryService ragTraceQueryService;

    /**
     * 分页查询链路运行记录
     */
    @GetMapping("/rag/traces/runs")
    public Result<IPage<RagTraceRunVO>> pageRuns(RagTraceRunPageRequest request) {
        return Results.success(ragTraceQueryService.pageRuns(request));
    }

    /**
     * 查询链路详情（包含节点）
     */
    @GetMapping("/rag/traces/runs/{traceId}")
    public Result<RagTraceDetailVO> detail(@PathVariable String traceId) {
        return Results.success(ragTraceQueryService.detail(traceId));
    }

    /**
     * 仅查询链路节点
     */
    @GetMapping("/rag/traces/runs/{traceId}/nodes")
    public Result<List<RagTraceNodeVO>> nodes(@PathVariable String traceId) {
        return Results.success(ragTraceQueryService.listNodes(traceId));
    }
}
