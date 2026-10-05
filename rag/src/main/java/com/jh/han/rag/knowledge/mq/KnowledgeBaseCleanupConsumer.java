
package com.jh.han.rag.knowledge.mq;

import com.jh.han.rag.framework.exception.ServiceException;
import com.jh.han.rag.knowledge.mq.event.KnowledgeBaseCleanupEvent;
import com.jh.han.rag.rag.core.graph.LightRagClient;
import com.jh.han.rag.rag.core.keyword.KeywordIndexService;
import com.jh.han.rag.rag.core.vector.VectorStoreAdmin;
import com.jh.han.rag.rag.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 知识库删除清理本地事件监听器
 * 负责异步回收知识库独占的底层物理资源：向量数据、bucket、ES 关键词索引、知识图谱数据
 * <p>
 * 各清理项 best-effort 互不影响，存在失败项则抛异常触发重试；所有操作均幂等，重试安全
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeBaseCleanupConsumer {

    private final VectorStoreAdmin vectorStoreAdmin;
    private final FileStorageService fileStorageService;
    /**
     * 关键词索引实现惰性解析：rag.keyword.type=none 时无该 bean，getIfAvailable() 返回 null 即跳过 ES 清理
     */
    private final ObjectProvider<KeywordIndexService> keywordIndexServiceProvider;
    /**
     * 图谱客户端惰性解析：rag.graph.type=none 时无该 bean，getIfAvailable() 返回 null 即跳过图谱清理
     */
    private final ObjectProvider<LightRagClient> lightRagClientProvider;

    @Async("knowledgeCleanupExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEvent(KnowledgeBaseCleanupEvent event) {
        String collectionName = event.getCollectionName();

        log.info("[消费者] 开始清理知识库物理资源，kbId={}, collectionName={}", event.getKbId(), collectionName);

        boolean allSucceeded = true;

        try {
            vectorStoreAdmin.dropVectorSpace(collectionName);
        } catch (Exception e) {
            allSucceeded = false;
            log.error("清理向量空间失败，collectionName={}", collectionName, e);
        }

        try {
            fileStorageService.deleteKnowledgeSpace(collectionName);
        } catch (Exception e) {
            allSucceeded = false;
            log.error("删除知识库存储目录失败，namespace={}", collectionName, e);
        }

        KeywordIndexService keywordIndexService = keywordIndexServiceProvider.getIfAvailable();
        if (keywordIndexService != null) {
            try {
                keywordIndexService.deleteByCollection(collectionName);
            } catch (Exception e) {
                allSucceeded = false;
                log.error("删除 ES 关键词索引失败，collectionName={}", collectionName, e);
            }
        }

        LightRagClient lightRagClient = lightRagClientProvider.getIfAvailable();
        if (lightRagClient != null) {
            try {
                lightRagClient.deleteByCollection(collectionName);
            } catch (Exception e) {
                allSucceeded = false;
                log.error("删除 LightRAG 图谱数据失败，collectionName={}", collectionName, e);
            }
        }

        if (!allSucceeded) {
            throw new ServiceException("知识库物理资源清理存在失败项，触发重试");
        }
    }
}
