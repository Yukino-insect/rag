
package com.jh.han.rag.knowledge.mq;

import com.jh.han.rag.framework.context.LoginUser;
import com.jh.han.rag.framework.context.UserContext;
import com.jh.han.rag.knowledge.mq.event.KnowledgeDocumentChunkEvent;
import com.jh.han.rag.knowledge.service.KnowledgeDocumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 文档分块本地事件监听器
 * 负责异步执行耗时的文本提取、分块、向量嵌入及写库操作
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeDocumentChunkConsumer {

    private final KnowledgeDocumentService documentService;

    @Async("knowledgeChunkExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEvent(KnowledgeDocumentChunkEvent event) {

        log.info("[本地事件] 开始执行文档分块任务，docId={}", event.getDocId());

        UserContext.set(LoginUser.builder().username(event.getOperator()).build());
        try {
            documentService.executeChunk(event.getDocId());
        } finally {
            UserContext.clear();
        }
    }
}
