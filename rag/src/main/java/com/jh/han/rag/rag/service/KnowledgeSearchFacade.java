package com.jh.han.rag.rag.service;

import com.jh.han.rag.framework.convention.ChatMessage;
import com.jh.han.rag.framework.convention.ChatRequest;
import com.jh.han.rag.infra.chat.LLMService;
import com.jh.han.rag.rag.core.guidance.GuidanceDecision;
import com.jh.han.rag.rag.core.guidance.IntentGuidanceService;
import com.jh.han.rag.rag.core.intent.IntentResolver;
import com.jh.han.rag.rag.core.prompt.PromptContext;
import com.jh.han.rag.rag.core.prompt.RAGPromptService;
import com.jh.han.rag.rag.core.retrieval.RetrievalEngine;
import com.jh.han.rag.rag.core.rewrite.QueryRewriteService;
import com.jh.han.rag.rag.core.rewrite.RewriteResult;
import com.jh.han.rag.rag.core.source.CitationContextEnricher;
import com.jh.han.rag.rag.dto.RetrievalContext;
import com.jh.han.rag.rag.dto.SubQuestionIntent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Agent 模式向 RAG 暴露的唯一检索门面。
 * <p>
 * 主 Agent 已经拥有会话状态，因此这里仅处理本次独立查询：改写、意图解析、歧义引导、
 * 检索以及基于 {@code KB_ANSWER} 槽位的证据合成。它不会执行 MCP 工具；MCP 由主 Agent
 * 直接通过 {@code McpToolRegistry} 调用，避免让同一个动作被两层编排重复执行。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeSearchFacade {

    private static final String EMPTY_RESULT = "未在知识库中检索到与该问题相关的内容。";

    private final QueryRewriteService queryRewriteService;
    private final IntentResolver intentResolver;
    private final IntentGuidanceService guidanceService;
    private final RetrievalEngine retrievalEngine;
    private final CitationContextEnricher citationContextEnricher;
    private final RAGPromptService promptService;
    private final LLMService llmService;

    /**
     * 检索并合成可被主 Agent 直接引用的知识库答案。
     */
    public String search(String query) {
        RewriteResult rewriteResult = queryRewriteService.rewriteWithSplit(query, List.of());
        List<SubQuestionIntent> subIntents = intentResolver.resolve(rewriteResult);

        GuidanceDecision guidance = guidanceService.detectAmbiguity(
                rewriteResult.rewrittenQuestion(), subIntents);
        if (guidance.isPrompt()) {
            log.info("Agent 知识检索命中歧义引导，跳过检索与合成, question={}",
                    rewriteResult.rewrittenQuestion());
            return guidance.getPrompt();
        }

        RetrievalContext retrievalContext = retrievalEngine.retrieve(subIntents);
        if (!retrievalContext.hasKb()) {
            return EMPTY_RESULT;
        }

        // Agent 工具结果没有配套来源面板，不能把内部文档 ID 或引用编号泄露给主模型。
        String kbContext = citationContextEnricher.enrich(retrievalContext.getKbContext(), List.of());
        PromptContext promptContext = PromptContext.builder()
                .kbContext(kbContext)
                .kbIntents(intentResolver.mergeIntentGroup(subIntents).kbIntents())
                .eligibleIntentIds(retrievalContext.getEligibleIntentIds())
                .build();
        List<ChatMessage> messages = promptService.buildStructuredMessages(
                promptContext, List.of(), rewriteResult.rewrittenQuestion(), rewriteResult.subQuestions(), false);

        return llmService.chat(ChatRequest.builder()
                .messages(messages)
                .temperature(0D)
                .topP(1D)
                .thinking(false)
                .build());
    }
}
