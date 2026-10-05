package com.jh.han.rag.agent.config;

import cn.hutool.core.util.StrUtil;
import com.jh.han.rag.agent.tool.AgentToolCatalog;
import com.jh.han.rag.rag.core.prompt.AgentPromptResolver;
import com.jh.han.rag.rag.core.prompt.AgentPromptSlot;
import io.agentscope.core.ReActAgent;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * ReAct Agent 提供者。
 * <p>
 * 人设槽位在每次取用时检查；管理员切换或更新当前智能体后，下一轮请求会懒重建 Agent。
 * AgentScope 会在同一进程内按 userId/sessionId 维护运行状态；业务消息仍由既有 RAG
 * 会话表持久化。跨进程重启的 Agent 状态恢复需要单独的状态存储，刻意不把模板项目的
 * {@code t_agent_state} 表和分布式状态链路迁入本单机版本。
 */
@Component
@ConditionalOnAgentEngine
@RequiredArgsConstructor
public class ReActAgentProvider {

    private static final String AGENT_NAME = "RagAgent";

    private final AgentPromptResolver promptResolver;
    private final AgentToolCatalog toolCatalog;
    private final OpenAIChatModel agentChatModel;
    private final AgentProperties agentProperties;

    private volatile CachedAgent cached;

    public ReActAgent getAgent() {
        String persona = promptResolver.resolve(AgentPromptSlot.AGENT_MAIN);
        if (StrUtil.isBlank(persona)) {
            throw new IllegalStateException("当前激活智能体未配置 AGENT_MAIN 提示词");
        }
        CachedAgent current = cached;
        if (current != null && current.persona().equals(persona)) {
            return current.agent();
        }
        synchronized (this) {
            current = cached;
            if (current != null && current.persona().equals(persona)) {
                return current.agent();
            }
            ReActAgent agent = ReActAgent.builder()
                    .name(AGENT_NAME)
                    .sysPrompt(persona)
                    .model(agentChatModel)
                    .toolkit(toolCatalog.buildToolkit())
                    .maxIters(Math.max(1, agentProperties.getMaxIters()))
                    .maxRetries(Math.max(1, agentProperties.getMaxRetries()))
                    .build();
            cached = new CachedAgent(persona, agent);
            return agent;
        }
    }

    private record CachedAgent(String persona, ReActAgent agent) {
    }
}
