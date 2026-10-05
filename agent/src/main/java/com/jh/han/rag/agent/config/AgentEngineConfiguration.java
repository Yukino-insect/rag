package com.jh.han.rag.agent.config;

import cn.hutool.core.util.StrUtil;
import com.jh.han.rag.infra.config.AIModelProperties;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/** AgentScope 模型装配，复用当前应用已有的 OpenAI 兼容供应商配置。 */
@Configuration
@ConditionalOnAgentEngine
@RequiredArgsConstructor
public class AgentEngineConfiguration {

    private final AgentProperties agentProperties;
    private final AIModelProperties aiModelProperties;

    @Bean
    public OpenAIChatModel agentChatModel() {
        AgentProperties.Chat chat = agentProperties.getChat();
        if (chat == null || StrUtil.isBlank(chat.getProvider()) || StrUtil.isBlank(chat.getModel())) {
            throw new IllegalStateException("agent.chat.provider / agent.chat.model 未配置");
        }
        Map<String, AIModelProperties.ProviderConfig> providers = aiModelProperties.getProviders();
        AIModelProperties.ProviderConfig provider = providers == null ? null : providers.get(chat.getProvider());
        if (provider == null) {
            throw new IllegalStateException("agent.chat.provider 在 ai.providers 中不存在: " + chat.getProvider());
        }
        String endpointPath = provider.getEndpoints() == null ? null : provider.getEndpoints().get("chat");
        if (StrUtil.isBlank(provider.getUrl()) || StrUtil.isBlank(endpointPath)) {
            throw new IllegalStateException("供应商缺少 url 或 endpoints.chat: " + chat.getProvider());
        }
        return OpenAIChatModel.builder()
                .baseUrl(provider.getUrl())
                .endpointPath(endpointPath)
                .apiKey(provider.getApiKey())
                .modelName(chat.getModel())
                .stream(true)
                .nativeStructuredOutputWithTools(false)
                .build();
    }
}
