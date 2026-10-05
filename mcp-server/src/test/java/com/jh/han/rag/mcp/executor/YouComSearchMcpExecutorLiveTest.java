
package com.jh.han.rag.mcp.executor;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * youcom_search MCP 工具真实 API 集成测试
 * <p>
 * 仅当环境变量 YDC_API_KEY 存在时运行（1 次真实调用），日常构建自动跳过
 */
@DisplayName("youcom_search MCP 工具（真实 API）")
@EnabledIfEnvironmentVariable(named = "YDC_API_KEY", matches = ".+")
class YouComSearchMcpExecutorLiveTest {

    @Test
    @DisplayName("真实检索返回编号结果文本")
    void liveSearchReturnsFormattedResults() {
        YouComSearchMcpExecutor executor = new YouComSearchMcpExecutor();

        CallToolResult result = executor.handleCall(new CallToolRequest("youcom_search",
                Map.of("query", "Model Context Protocol", "count", 3)));

        assertFalse(result.isError(), "真实调用不应返回错误");
        String text = ((TextContent) result.content().get(0)).text();
        assertTrue(text.contains("1. "), "结果应包含编号列表");
        assertTrue(text.contains("链接: "), "结果应包含来源链接");
        System.out.println("[LIVE] youcom_search 返回:\n" + text);
    }
}
