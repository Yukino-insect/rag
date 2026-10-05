package com.jh.han.rag.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Agent 执行架构配置。模型供应商引用 {@code ai.providers} 中的同名配置。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "agent")
public class AgentProperties {

    private Chat chat = new Chat();

    /** ReAct 循环最大轮数。 */
    private Integer maxIters = 10;

    /** 单次模型调用最大尝试次数（包含首次调用）。 */
    private Integer maxRetries = 1;

    /** Agent SSE 通道超时。 */
    private Long sseTimeoutMs = 900_000L;

    @Data
    public static class Chat {

        /** {@code ai.providers} 下的供应商 key。 */
        private String provider;

        /** 传给 OpenAI 兼容端点的模型名。 */
        private String model;
    }
}
