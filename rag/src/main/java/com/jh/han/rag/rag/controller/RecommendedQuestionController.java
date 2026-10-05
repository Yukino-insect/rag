
package com.jh.han.rag.rag.controller;

import com.jh.han.rag.framework.context.UserContext;
import com.jh.han.rag.framework.convention.Result;
import com.jh.han.rag.framework.web.Results;
import com.jh.han.rag.rag.dto.RecommendedQuestionsPayload;
import com.jh.han.rag.rag.service.RecommendedQuestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 推荐追问问题控制器
 * <p>
 * 答案完成后按需触发，POST 幂等生成推荐追问并落库，不占用 chat 流式关键路径
 */
@RestController
@RequiredArgsConstructor
public class RecommendedQuestionController {

    private final RecommendedQuestionService recommendedQuestionService;

    /**
     * 生成推荐追问问题
     */
    @PostMapping("/conversations/messages/{messageId}/recommended-questions")
    public Result<RecommendedQuestionsPayload> generate(@PathVariable String messageId) {
        return Results.success(recommendedQuestionService.generate(messageId, UserContext.getUserId()));
    }
}
