
package com.jh.han.rag;

import com.mzt.logapi.starter.annotation.EnableLogRecord;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * rag 模块测试启动配置，注解镜像 bootstrap 的 RagApplication
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
public class TestRagApplication {
}
