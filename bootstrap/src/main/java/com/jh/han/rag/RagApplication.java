package com.jh.han.rag;

import com.mzt.logapi.starter.annotation.EnableLogRecord;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Rag 核心应用启动类
 */
@SpringBootApplication
@EnableScheduling
@EnableLogRecord(tenant = "rag", proxyTargetClass = true)
@MapperScan(basePackages = {
        "com.jh.han.rag.rag.dao.mapper",
        "com.jh.han.rag.ingestion.dao.mapper",
        "com.jh.han.rag.knowledge.dao.mapper",
        "com.jh.han.rag.user.dao.mapper",
        "com.jh.han.rag.audit.dao.mapper"
})
public class RagApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagApplication.class, args);
    }
}
