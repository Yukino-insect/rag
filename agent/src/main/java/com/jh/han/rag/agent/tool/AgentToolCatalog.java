package com.jh.han.rag.agent.tool;

import com.jh.han.rag.rag.core.mcp.McpToolRegistry;
import com.jh.han.rag.rag.service.KnowledgeSearchFacade;
import io.agentscope.core.tool.Toolkit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 单机 Agent 的工具目录。
 * <p>
 * 固定注册知识库工具，再将 RAG 模块已发现的 MCP 工具逐一转换为 AgentScope 工具。工具发现
 * 仍只发生在 RAG 的官方 MCP 客户端层，Agent 不新建第二套连接。
 */
@Component
@RequiredArgsConstructor
public class AgentToolCatalog {

    private final KnowledgeSearchFacade knowledgeSearchFacade;
    private final McpToolRegistry mcpToolRegistry;

    public Toolkit buildToolkit() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(new KnowledgeSearchTool(knowledgeSearchFacade));
        mcpToolRegistry.listAllExecutors().forEach(executor -> {
            if (!KnowledgeSearchTool.TOOL_NAME.equals(executor.getToolId())) {
                toolkit.registerAgentTool(new RegisteredMcpTool(executor));
            }
        });
        return toolkit;
    }
}
