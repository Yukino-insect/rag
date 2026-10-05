
package com.jh.han.rag.infra.rerank;

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
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class CustomizeRerankClient extends AbstractRerankClient{

    @Qualifier("syncHttpClient")
    private final OkHttpClient httpClient;

    @Override
    public boolean requiresApiKey() {
        return false;
    }

    @Override
    public String provider() {
        return ModelProvider.CUSTOMIZE.getId();
    }

    @Override
    protected List<RetrievedChunk> doRerank(String query, List<RetrievedChunk> candidates, int topN, ModelTarget target) {
        AIModelProperties.ProviderConfig provider = HttpResponseHelper.requireProvider(target, provider());

        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("query", query);
        requestBody.add("documents", buildDocumentsArray(candidates));
        requestBody.addProperty("top_n", topN);

        Request request = new Request.Builder()
                .url(ModelUrlResolver.resolveUrl(provider, target.candidate(), ModelCapability.RERANK))
                .post(RequestBody.create(requestBody.toString(), HttpMediaTypes.JSON))
                .build();

        JsonObject responseJson = doPost(() -> httpClient.newCall(request).execute(), log);

        if (responseJson == null || !responseJson.has("success")) {
            throw new ModelClientException(provider() + " rerank 响应缺少 success", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        if (!responseJson.get("success").getAsBoolean()) {
            String err = responseJson.has("err") && !responseJson.get("err").isJsonNull()
                    ? responseJson.get("err").getAsString()
                    : "unknown";
            log.error("重排序失败；err={}", err);
            throw new ModelClientException(provider() + " rerank 失败: " + err, ModelClientErrorType.INVALID_RESPONSE, null);
        }

        JsonArray results = responseJson.getAsJsonArray("results");
        if (results == null) {
            throw new ModelClientException(provider() + " rerank 响应缺少 results", ModelClientErrorType.INVALID_RESPONSE, null);
        }

        return mapRankedResults(parseRankedChunks(results, "score"), candidates, topN, false);
    }
}
