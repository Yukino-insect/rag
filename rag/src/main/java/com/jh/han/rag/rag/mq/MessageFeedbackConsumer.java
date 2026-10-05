
package com.jh.han.rag.rag.mq;

import com.jh.han.rag.rag.mq.event.MessageFeedbackEvent;
import com.jh.han.rag.rag.service.MessageFeedbackService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 消息反馈本地事件监听器，负责将点赞/点踩异步持久化到数据库
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageFeedbackConsumer {

    private final MessageFeedbackService feedbackService;

    @Async("feedbackEventExecutor")
    @EventListener
    public void onEvent(MessageFeedbackEvent event) {

        log.info("[本地事件] 开始处理反馈事件，messageId: {}, userId: {}, vote: {}, cancelled: {}",
                event.getMessageId(), event.getUserId(), event.getVote(), event.isCancelled());
        feedbackService.submitFeedbackByEvent(event);
    }
}
