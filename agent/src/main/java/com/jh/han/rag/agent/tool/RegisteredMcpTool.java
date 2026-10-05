package com.jh.han.rag.agent.tool;

import cn.hutool.core.util.StrUtil;
import com.jh.han.rag.rag.core.mcp.McpToolExecutor;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 适配当前 RAG 模块已经发现并注册的 MCP 工具。
 * <p>
 * 不使用 AgentScope 自带的 MCP 客户端，避免其传递依赖的旧 MCP SDK 与本项目 SDK 冲突；
 * 连接、认证、重试和工具发现均继续由 {@link McpToolExecutor} 所在的 RAG 桥接层负责。
 */
public class RegisteredMcpTool implements AgentTool {

    private final McpToolExecutor executor;
    private final Tool definition;

    public RegisteredMcpTool(McpToolExecutor executor) {
        this.executor = executor;
        this.definition = executor.getToolDefinition();
    }

    @Override
    public String getName() {
        return definition.name();
    }

    @Override
    public String getDescription() {
        return StrUtil.blankToDefault(definition.description(), "调用企业已接入的 MCP 工具。");
    }

    @Override
    public Map<String, Object> getParameters() {
        if (definition.inputSchema() == null) {
            return Map.of("type", "object", "properties", Map.of(), "additionalProperties", true);
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", definition.inputSchema().properties());
        // MCP 的 JsonSchema 可能含额外扩展字段；只透传模型调用真正需要的属性定义，避免 SDK 版本耦合。
        schema.put("additionalProperties", false);
        return schema;
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
        try {
            CallToolResult result = executor.execute(param == null || param.getInput() == null ? Map.of() : param.getInput());
            boolean error = result == null || Boolean.TRUE.equals(result.isError());
            return result(callId, extractText(result), error);
        } catch (Exception exception) {
            return result(callId, "工具调用失败，请稍后重试。", true);
        }
    }

    private String extractText(CallToolResult result) {
        if (result == null || result.content() == null) {
            return "工具未返回内容。";
        }
        List<String> texts = result.content().stream()
                .filter(TextContent.class::isInstance)
                .map(TextContent.class::cast)
                .map(TextContent::text)
                .toList();
        return texts.isEmpty() ? "工具已执行，但没有可展示的文本结果。" : String.join("\n", texts);
    }

    private ToolResultBlock result(String callId, String text, boolean error) {
        return ToolResultBlock.builder()
                .id(callId)
                .name(getName())
                .output(TextBlock.builder().text(text).build())
                .state(error ? ToolResultState.ERROR : ToolResultState.SUCCESS)
                .build();
    }
}
