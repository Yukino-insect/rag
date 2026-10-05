
package com.jh.han.rag.rag.service.pipeline;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.jh.han.rag.framework.convention.ChatMessage;
import com.jh.han.rag.framework.convention.ChatRequest;
import com.jh.han.rag.framework.convention.SourceRef;
import com.jh.han.rag.infra.chat.LLMService;
import com.jh.han.rag.infra.chat.StreamCallback;
import com.jh.han.rag.infra.chat.StreamCancellationHandle;
import com.jh.han.rag.rag.core.guidance.GuidanceDecision;
import com.jh.han.rag.rag.core.guidance.IntentGuidanceService;
import com.jh.han.rag.rag.core.intent.IntentResolver;
import com.jh.han.rag.rag.core.memory.ConversationMemoryService;
import com.jh.han.rag.rag.core.prompt.AgentPromptResolver;
import com.jh.han.rag.rag.core.prompt.AgentPromptSlot;
import com.jh.han.rag.rag.core.prompt.PromptContext;
import com.jh.han.rag.rag.core.prompt.RAGPromptService;
import com.jh.han.rag.rag.core.retrieval.RetrievalEngine;
import com.jh.han.rag.rag.core.rewrite.QueryRewriteService;
import com.jh.han.rag.rag.core.rewrite.RewriteResult;
import com.jh.han.rag.rag.core.source.CitationContextEnricher;
import com.jh.han.rag.rag.core.source.GroundingChunksAssembler;
import com.jh.han.rag.rag.core.source.SourcesAssembler;
import com.jh.han.rag.rag.dto.IntentGroup;
import com.jh.han.rag.rag.dto.RetrievalContext;
import com.jh.han.rag.rag.dto.SubQuestionIntent;
import com.jh.han.rag.framework.web.LocalStreamTaskManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 流式对话流水线
 * <p>
 * 承载从 RAGChatServiceImpl 提取的业务编排逻辑：
 * 记忆加载 -> 改写拆分 -> 意图解析 -> 歧义引导 -> 系统响应 / 检索 -> Prompt 组装 -> 流式输出
 * <p>
 * 流水线模式：通过私有方法 + boolean 返回值（handleXxx 返回 true 表示已处理并短路）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StreamChatPipeline {

    private final ConversationMemoryService memoryService;
    private final QueryRewriteService queryRewriteService;
    private final IntentResolver intentResolver;
    private final IntentGuidanceService guidanceService;
    private final RetrievalEngine retrievalEngine;
    private final LLMService llmService;
    private final RAGPromptService promptBuilder;
    private final AgentPromptResolver agentPromptResolver;
    private final LocalStreamTaskManager taskManager;
    private final SourcesAssembler sourcesAssembler;
    private final GroundingChunksAssembler groundingChunksAssembler;
    private final CitationContextEnricher citationContextEnricher;

    /**
     * 执行流式对话管道
     */
    public void execute(StreamChatContext ctx) {
        loadMemory(ctx); // 加载会话摘要和历史消息
        rewriteQuery(ctx); // 对原始问题进行重写并生成一些子问题
        resolveIntents(ctx); // 对子问题进行意图解析，并匹配每一个意图节点

        // 如果问题有歧义，直接返回“澄清提示”
        if (handleGuidance(ctx)) {
            return;
        }

        // 如果全部命中“系统类意图”，直接走系统问答，不做检索
        if (handleSystemOnly(ctx)) {
            return;
        }

        // 按意图执行知识库/MCP 检索
        // 1. 首先先构造检索上下文
        RetrievalContext retrievalCtx = retrieve(ctx);
        // 2. 如果没有识别出意图直接返回
        if (handleEmptyRetrieval(ctx, retrievalCtx)) {
            return;
        }

        // 3. 否则
        streamRagResponse(ctx, retrievalCtx);
    }

    // ==================== 流水线阶段 ====================

    private void loadMemory(StreamChatContext ctx) {
        // 查询用户当前的历史对话
        List<ChatMessage> history = memoryService.load(ctx.getConversationId(), ctx.getUserId());
        // 追加当前提问
        String questionMessageId = memoryService.append(
                ctx.getConversationId(), ctx.getUserId(), ChatMessage.user(ctx.getQuestion()));
        // 注册当前提问的 messageId
        ctx.getCallback().onReplyToMessageId(questionMessageId);
        // 注册对话历史
        ctx.setHistory(history);
    }

    private void rewriteQuery(StreamChatContext ctx) {
        RewriteResult rewriteResult = queryRewriteService.rewriteWithSplit(ctx.getQuestion(), ctx.getHistory());
        ctx.setRewriteResult(rewriteResult);
    }

    private void resolveIntents(StreamChatContext ctx) {
        List<SubQuestionIntent> subIntents = intentResolver.resolve(ctx.getRewriteResult());
        ctx.setSubIntents(subIntents);
    }

    private boolean handleGuidance(StreamChatContext ctx) {
        // 检查歧义
        GuidanceDecision decision = guidanceService.detectAmbiguity(
                ctx.getRewriteResult().rewrittenQuestion(),
                ctx.getSubIntents()
        );

        // 如果没有歧义
        if (!decision.isPrompt()) {
            return false;
        }
        // 如果有歧义的话，直接结束对话
        StreamCallback callback = ctx.getCallback();
        callback.onContent(decision.getPrompt());
        callback.onComplete();
        return true;
    }

    private boolean handleSystemOnly(StreamChatContext ctx) {
        List<SubQuestionIntent> subIntents = ctx.getSubIntents();
        boolean allSystemOnly = subIntents.stream()
                .allMatch(si -> intentResolver.isSystemOnly(si.nodeScores()));
        // 如果存在非系统意图
        if (!allSystemOnly) {
            return false;
        }
        // 如果全部命中系统意图，直接返回
        String customPrompt = subIntents.stream()
                .flatMap(si -> si.nodeScores().stream())
                .map(ns -> ns.getNode().getPromptTemplate())
                .filter(StrUtil::isNotBlank)
                .findFirst()
                .orElse(null);
        StreamCancellationHandle handle = streamSystemResponse(
                ctx.getRewriteResult().rewrittenQuestion(),
                ctx.getHistory(),
                customPrompt,
                ctx.getCallback()
        );
        taskManager.bindHandle(ctx.getTaskId(), handle == null ? null : handle::cancel);
        return true;
    }

    private RetrievalContext retrieve(StreamChatContext ctx) {
        return retrievalEngine.retrieve(ctx.getSubIntents());
    }

    private boolean handleEmptyRetrieval(StreamChatContext ctx, RetrievalContext retrievalCtx) {
        if (!retrievalCtx.isEmpty()) {
            return false;
        }
        StreamCallback callback = ctx.getCallback();
        callback.onContent("未检索到与问题相关的文档内容。");
        callback.onComplete();
        return true;
    }

    private void streamRagResponse(StreamChatContext ctx, RetrievalContext retrievalCtx) {
        // 聚合所有意图用于 prompt 规划
        IntentGroup mergedGroup = intentResolver.mergeIntentGroup(ctx.getSubIntents());

        // 检索完成后建立唯一来源编号：同一列表用于完成事件、来源面板与消息落库，开启引用时还作为行内角标编号
        List<SourceRef> sources = sourcesAssembler.assemble(retrievalCtx.getIntentChunks());
        ctx.getCallback().onSources(sources);
        // 开关关闭时这一步只负责清掉上下文里的内部 docId，不注入编号
        retrievalCtx.setKbContext(citationContextEnricher.enrich(retrievalCtx.getKbContext(), sources));

        // 装配 grounding 片段随消息落库 供答案后推荐追问生成 grounding（不参与 prompt）
        ctx.getCallback().onGroundingChunks(groundingChunksAssembler.assemble(retrievalCtx.getIntentChunks()));

        StreamCancellationHandle handle = streamLLMResponse(
                ctx.getRewriteResult(),
                retrievalCtx,
                mergedGroup,
                ctx.getHistory(),
                ctx.isDeepThinking(),
                ctx.getCallback()
        );
        taskManager.bindHandle(ctx.getTaskId(), handle == null ? null : handle::cancel);
    }

    // ==================== LLM 响应 ====================

    private StreamCancellationHandle streamSystemResponse(String question, List<ChatMessage> history,
                                                          String customPrompt, StreamCallback callback) {
        String systemPrompt = StrUtil.isNotBlank(customPrompt)
                ? customPrompt
                : agentPromptResolver.resolve(AgentPromptSlot.SYSTEM_CHAT);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(systemPrompt));
        if (CollUtil.isNotEmpty(history)) {
            messages.addAll(history);
        }
        messages.add(ChatMessage.user(question));

        ChatRequest req = ChatRequest.builder()
                .messages(messages)
                .temperature(0.7D)
                .thinking(false)
                .build();
        return llmService.streamChat(req, callback);
    }

    private StreamCancellationHandle streamLLMResponse(RewriteResult rewriteResult, RetrievalContext ctx,
                                                       IntentGroup intentGroup, List<ChatMessage> history,
                                                       boolean deepThinking, StreamCallback callback) {
        PromptContext promptContext = PromptContext.builder()
                .question(rewriteResult.rewrittenQuestion())
                .mcpContext(ctx.getMcpContext())
                .kbContext(ctx.getKbContext())
                .mcpIntents(intentGroup.mcpIntents())
                .kbIntents(intentGroup.kbIntents())
                .eligibleIntentIds(ctx.getEligibleIntentIds())
                .build();

        List<ChatMessage> messages = promptBuilder.buildStructuredMessages(
                promptContext,
                history,
                rewriteResult.rewrittenQuestion(),
                rewriteResult.subQuestions()  // 传入子问题列表
        );
        ChatRequest chatRequest = ChatRequest.builder()
                .messages(messages)
                .thinking(deepThinking)
                .temperature(ctx.hasMcp() ? 0.3D : 0D)  // MCP 场景稍微放宽温度
                .topP(ctx.hasMcp() ? 0.8D : 1D)
                .build();

        return llmService.streamChat(chatRequest, callback);
    }
}
