
package com.jh.han.rag.infra.chat;

import com.jh.han.rag.framework.convention.ChatRequest;
import com.jh.han.rag.framework.trace.RagTraceNode;
import com.jh.han.rag.infra.enums.ModelProvider;
import com.jh.han.rag.infra.model.ModelTarget;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class OllamaChatClient extends AbstractOpenAIStyleChatClient {

    @Override
    public String provider() {
        return ModelProvider.OLLAMA.getId();
    }

    @Override
    protected boolean requiresApiKey() {
        return false;
    }

    @Override
    @RagTraceNode(name = "ollama-chat", type = "LLM_PROVIDER")
    public String chat(ChatRequest request, ModelTarget target) {
        return doChat(request, target);
    }

    @Override
    public StreamCancellationHandle streamChat(ChatRequest request, StreamCallback callback, ModelTarget target) {
        return doStreamChat(request, callback, target);
    }
}
