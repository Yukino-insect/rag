
package com.jh.han.rag.rag.config;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * RAG 单机并发限流配置。
 *
 * <p>聊天请求由 {@code chatEntryExecutor} 的固定大小线程池直接承接；不再引入
 * Redis 队列、Lua 脚本或跨节点租约。应用需要水平扩展时，应在网关层做限流，
 * 而不是重新把分布式协调逻辑塞回 RAG 进程。</p>
 */
@Data
@Configuration
public class RAGRateLimitProperties {

    /**
     * 最大并发数
     */
    @Value("${rag.rate-limit.global.max-concurrent:50}")
    private Integer globalMaxConcurrent;

}
