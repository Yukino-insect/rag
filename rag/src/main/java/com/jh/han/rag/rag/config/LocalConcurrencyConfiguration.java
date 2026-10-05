
package com.jh.han.rag.rag.config;

import com.jh.han.rag.core.parser.mineru.MinerUProperties;
import com.jh.han.rag.knowledge.config.RagSemaphoreProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.concurrent.Semaphore;

/**
 * 单机部署使用的本地并发闸门。
 */
@Configuration
@EnableAsync
public class LocalConcurrencyConfiguration {

    @Bean
    public Semaphore documentUploadSemaphore(RagSemaphoreProperties properties) {
        return new Semaphore(properties.getDocumentUpload().getMaxConcurrent(), true);
    }

    @Bean
    public Semaphore mineruParserSemaphore(MinerUProperties properties) {
        return new Semaphore(properties.getConcurrencyLimit(), true);
    }
}
