
package com.jh.han.rag.rag.controller;

import com.jh.han.rag.rag.controller.request.MessageFeedbackRequest;
import com.jh.han.rag.framework.convention.Result;
import com.jh.han.rag.framework.web.Results;
import com.jh.han.rag.rag.service.MessageFeedbackService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会话消息反馈控制器
 */
@RestController
@RequiredArgsConstructor
public class MessageFeedbackController {

    private final MessageFeedbackService feedbackService;

    /**
     * 提交点赞/踩反馈（异步，通过 MQ 持久化）
     */
    @PostMapping("/conversations/messages/{messageId}/feedback")
    public Result<Void> submitFeedback(@PathVariable String messageId,
                                       @RequestBody MessageFeedbackRequest request) {
        feedbackService.submitFeedbackAsync(messageId, request);
        return Results.success();
    }

    /**
     * 取消点赞/踩反馈（异步，通过 MQ 持久化）
     */
    @DeleteMapping("/conversations/messages/{messageId}/feedback")
    public Result<Void> cancelFeedback(@PathVariable String messageId) {
        feedbackService.cancelFeedbackAsync(messageId);
        return Results.success();
    }
}
