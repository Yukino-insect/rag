
package com.jh.han.rag.infra.rerank;

import com.jh.han.rag.framework.convention.RetrievedChunk;
import com.jh.han.rag.infra.enums.ModelProvider;
import com.jh.han.rag.infra.model.ModelTarget;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class NoopRerankClient extends AbstractRerankClient {

    @Override
    public boolean requiresApiKey() {
        return false;
    }

    @Override
    public String provider() {
        return ModelProvider.NOOP.getId();
    }

    @Override
    protected List<RetrievedChunk> doRerank(String query, List<RetrievedChunk> candidates, int topN, ModelTarget target) {
        return candidates.stream()
                .limit(topN)
                .collect(Collectors.toList());
    }
}
