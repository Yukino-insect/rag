
package com.jh.han.rag.rag.core.graph;

import com.jh.han.rag.framework.convention.RetrievedChunk;

import java.util.List;

/**
 * 图谱证据按知识库归属的切分结果
 * <p>
 * LightRAG 单实例即单图、一次查询看的就是全图，归属只能在结果侧按 file_path 判定，
 * 故由 client 判归属、通道分名额：过滤条件为空时全部落在 {@code matched}
 *
 * @param matched   命中目标库的证据，按图谱名次有序
 * @param unmatched 不属于目标库的证据，按图谱名次有序
 */
public record GraphEvidence(List<RetrievedChunk> matched, List<RetrievedChunk> unmatched) {

    public static GraphEvidence empty() {
        return new GraphEvidence(List.of(), List.of());
    }
}
