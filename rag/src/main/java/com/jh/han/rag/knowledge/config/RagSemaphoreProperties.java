
package com.jh.han.rag.knowledge.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * 单机信号量配置
 */
@Data
@Validated
@Configuration
@ConfigurationProperties(prefix = "rag.semaphore")
public class RagSemaphoreProperties {

    @Valid
    private LocalSemaphoreConfig documentUpload = new LocalSemaphoreConfig();

    @Data
    public static class LocalSemaphoreConfig {

        /**
         * 最大并发数
         */
        @Min(1)
        private Integer maxConcurrent = 10;

        /**
         * 获取许可最大等待时间（秒）
         */
        @Min(0)
        private Integer maxWaitSeconds = 30;

    }
}
