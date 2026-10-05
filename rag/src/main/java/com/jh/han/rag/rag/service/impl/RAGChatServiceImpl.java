
package com.jh.han.rag.rag.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.jh.han.rag.framework.context.UserContext;
import com.jh.han.rag.infra.chat.StreamCallback;
import com.jh.han.rag.rag.service.ratelimit.ChatQueueLimiter;
import com.jh.han.rag.rag.service.RAGChatService;
import com.jh.han.rag.rag.service.handler.StreamCallbackFactory;
import com.jh.han.rag.framework.web.LocalStreamTaskManager;
import com.jh.han.rag.rag.service.pipeline.StreamChatContext;
import com.jh.han.rag.rag.service.pipeline.StreamChatPipeline;
import com.jh.han.rag.rag.trace.StreamChatTraceRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * RAG 对话服务默认实现
 */
@Primary
@Slf4j
@Service
@ConditionalOnProperty(prefix = "rag.engine", name = "type", havingValue = "workflow", matchIfMissing = true)
@RequiredArgsConstructor
public class RAGChatServiceImpl implements RAGChatService {

    private final StreamChatPipeline chatPipeline;
    private final ChatQueueLimiter chatQueueLimiter;
    private final StreamCallbackFactory callbackFactory;
    private final StreamChatTraceRunner traceRunner;
    private final LocalStreamTaskManager taskManager;

    @Override
    public void streamChat(String question, String conversationId, Boolean deepThinking, SseEmitter emitter) {
        // 如何会话 ID 为 null
        // 说明是新一轮的对话，为其分配一个新的会话 ID
        String actualConversationId = StrUtil.isBlank(conversationId) ? IdUtil.getSnowflakeNextIdStr() : conversationId;
        // 生成一个任务 ID
        // 用来日志追踪
        String taskId = IdUtil.getSnowflakeNextIdStr();
        StreamCallback callback = callbackFactory.createChatEventHandler(emitter, actualConversationId, taskId);

        // 限流
        chatQueueLimiter.enqueue(question, actualConversationId, emitter,
                () -> traceRunner.run(question, actualConversationId, taskId, callback, traceAware -> {
                    // 构建流式推送上下文
                    StreamChatContext ctx = StreamChatContext.builder()
                            .question(question)
                            .conversationId(actualConversationId)
                            .taskId(taskId)
                            .deepThinking(Boolean.TRUE.equals(deepThinking))
                            .userId(UserContext.getUserId())
                            .callback(traceAware)
                            .build();
                    chatPipeline.execute(ctx);
                })
        );
    }

    @Override
    public void stopTask(String taskId) {
        taskManager.cancel(taskId);
    }
}
