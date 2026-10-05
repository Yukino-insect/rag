package com.jh.han.rag.agent.tool;

import cn.hutool.core.util.StrUtil;
import com.jh.han.rag.rag.service.KnowledgeSearchFacade;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 将当前项目的知识库检索能力包装为 AgentScope 工具。 */
public class KnowledgeSearchTool implements AgentTool {

    public static final String TOOL_NAME = "search_knowledge";

    private final KnowledgeSearchFacade knowledgeSearchFacade;

    public KnowledgeSearchTool(KnowledgeSearchFacade knowledgeSearchFacade) {
        this.knowledgeSearchFacade = knowledgeSearchFacade;
    }

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public String getDescription() {
        return "检索企业内部知识库。用于制度、流程、产品说明和已收录文档相关的问题。"
                + "query 必须是脱离上下文也能独立理解的完整问题。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of("query", Map.of(
                        "type", "string",
                        "description", "用于检索知识库的完整、独立问题")),
                "required", List.of("query"),
                "additionalProperties", false);
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> execute(param)).subscribeOn(Schedulers.boundedElastic());
    }

    private ToolResultBlock execute(ToolCallParam param) {
        String callId = Optional.ofNullable(param)
                .map(ToolCallParam::getToolUseBlock)
                .map(ToolUseBlock::getId)
                .orElse(null);
        String query = Optional.ofNullable(param)
                .map(ToolCallParam::getInput)
                .map(input -> input.get("query"))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .map(String::strip)
                .filter(StrUtil::isNotBlank)
                .orElse(null);
        if (query == null) {
            return result(callId, "工具参数 query 不能为空", true);
        }
        try {
            String answer = knowledgeSearchFacade.search(query);
            return result(callId, StrUtil.blankToDefault(answer, "未检索到相关内容。"), false);
        } catch (Exception exception) {
            return result(callId, "知识库检索异常，请稍后重试。", true);
        }
    }

    private static ToolResultBlock result(String callId, String text, boolean error) {
        return ToolResultBlock.builder()
                .id(callId)
                .name(TOOL_NAME)
                .output(TextBlock.builder().text(text).build())
                .state(error ? ToolResultState.ERROR : ToolResultState.SUCCESS)
                .build();
    }
}
