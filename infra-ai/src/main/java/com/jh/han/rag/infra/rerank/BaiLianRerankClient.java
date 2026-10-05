
package com.jh.han.rag.infra.rerank;

import cn.hutool.core.collection.CollUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.jh.han.rag.framework.convention.RetrievedChunk;
import com.jh.han.rag.infra.config.AIModelProperties;
import com.jh.han.rag.infra.enums.ModelCapability;
import com.jh.han.rag.infra.enums.ModelProvider;
import com.jh.han.rag.infra.http.HttpMediaTypes;
import com.jh.han.rag.infra.http.HttpResponseHelper;
import com.jh.han.rag.infra.http.ModelClientErrorType;
import com.jh.han.rag.infra.http.ModelClientException;
import com.jh.han.rag.infra.http.ModelUrlResolver;
import com.jh.han.rag.infra.model.ModelTarget;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class BaiLianRerankClient extends AbstractRerankClient {

    @Qualifier("syncHttpClient")
    private final OkHttpClient httpClient;

    @Override
    public String provider() {
        return ModelProvider.BAI_LIAN.getId();
    }

    @Override
    protected List<RetrievedChunk> doRerank(String query, List<RetrievedChunk> candidates, int topN, ModelTarget target) {
        AIModelProperties.ProviderConfig provider = HttpResponseHelper.requireProvider(target, provider());

        JsonObject reqBody = new JsonObject();
        reqBody.addProperty("model", HttpResponseHelper.requireModel(target, provider()));

        JsonObject input = new JsonObject();
        input.addProperty("query", query);
        input.add("documents", buildDocumentsArray(candidates));

        JsonObject parameters = new JsonObject();
        parameters.addProperty("top_n", topN);
        parameters.addProperty("return_documents", true);

        reqBody.add("input", input);
        reqBody.add("parameters", parameters);

        Request request = new Request.Builder()
                .url(ModelUrlResolver.resolveUrl(provider, target.candidate(), ModelCapability.RERANK))
                .post(RequestBody.create(reqBody.toString(), HttpMediaTypes.JSON))
                .addHeader("Authorization", "Bearer " + provider.getApiKey())
                .build();

        JsonObject respJson = doPost(() -> httpClient.newCall(request).execute(), log);

        JsonObject output = requireOutput(respJson);

        JsonArray results = output.getAsJsonArray("results");
        if (CollUtil.isEmpty(results)) {
            throw new ModelClientException(provider() + " rerank results 为空", ModelClientErrorType.INVALID_RESPONSE, null);
        }

        return mapRankedResults(parseRankedChunks(results, "relevance_score"), candidates, topN, true);
    }

    private JsonObject requireOutput(JsonObject respJson) {
        if (respJson == null || !respJson.has("output")) {
            throw new ModelClientException(provider() + " rerank 响应缺少 output", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        JsonObject output = respJson.getAsJsonObject("output");
        if (output == null || !output.has("results")) {
            throw new ModelClientException(provider() + " rerank 响应缺少 results", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        return output;
    }
}
