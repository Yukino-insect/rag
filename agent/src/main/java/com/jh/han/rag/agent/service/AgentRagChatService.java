package com.jh.han.rag.agent.service;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.jh.han.rag.agent.config.ConditionalOnAgentEngine;
import com.jh.han.rag.agent.config.ReActAgentProvider;
import com.jh.han.rag.framework.context.UserContext;
import com.jh.han.rag.framework.convention.ChatMessage;
import com.jh.han.rag.framework.web.LocalStreamTaskManager;
import com.jh.han.rag.infra.chat.StreamCallback;
import com.jh.han.rag.rag.core.memory.ConversationMemoryService;
import com.jh.han.rag.rag.service.RAGChatService;
import com.jh.han.rag.rag.service.handler.StreamCallbackFactory;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.message.UserMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

/**
 * 将既有 {@code /rag/v3/chat} 接口切换为 AgentScope ReAct 执行器。
 * <p>
 * SSE 事件格式、会话 ID 和停止接口都沿用 workflow 链路，前端无需为单机 Agent 模式维护第二套协议。
 */
@Slf4j
@Service
@ConditionalOnAgentEngine
@RequiredArgsConstructor
public class AgentRagChatService implements RAGChatService {

    private final ReActAgentProvider agentProvider;
    private final ConversationMemoryService memoryService;
    private final StreamCallbackFactory callbackFactory;
    private final LocalStreamTaskManager taskManager;

    @Override
    public void streamChat(String question, String conversationId, Boolean deepThinking, SseEmitter emitter) {
        String actualConversationId = StrUtil.isBlank(conversationId) ? IdUtil.getSnowflakeNextIdStr() : conversationId;
        String taskId = IdUtil.getSnowflakeNextIdStr();
        String userId = UserContext.getUserId();
        StreamCallback callback = callbackFactory.createChatEventHandler(emitter, actualConversationId, taskId);
        String questionMessageId = memoryService.append(actualConversationId, userId, ChatMessage.user(question));
        callback.onReplyToMessageId(questionMessageId);

        ReActAgent agent = agentProvider.getAgent();
        RuntimeContext runtimeContext = RuntimeContext.builder()
                .userId(userId)
                .sessionId(actualConversationId)
                .build();
        try {
            Disposable subscription = agent.streamEvents(new UserMessage(question), runtimeContext)
                    .subscribe(event -> forward(event, callback), callback::onError, callback::onComplete);
            taskManager.bindHandle(taskId, () -> {
                subscription.dispose();
                agent.interrupt(userId, actualConversationId);
            });
        } catch (RuntimeException exception) {
            callback.onError(exception);
        }
    }

    private void forward(AgentEvent event, StreamCallback callback) {
        if (event instanceof TextBlockDeltaEvent textEvent) {
            callback.onContent(textEvent.getDelta());
        } else if (event instanceof ThinkingBlockDeltaEvent thinkingEvent) {
            callback.onThinking(thinkingEvent.getDelta());
        } else {
            log.debug("Agent 事件: {}", event.getClass().getSimpleName());
        }
    }

    @Override
    public void stopTask(String taskId) {
        taskManager.cancel(taskId);
    }
}
