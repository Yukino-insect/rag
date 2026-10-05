
package com.jh.han.rag.infra.rerank;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.jh.han.rag.framework.convention.RetrievedChunk;
import com.jh.han.rag.infra.http.HttpResponseHelper;
import com.jh.han.rag.infra.http.ModelClientErrorType;
import com.jh.han.rag.infra.http.ModelClientException;
import com.jh.han.rag.infra.model.ModelTarget;
import okhttp3.Response;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public abstract class AbstractRerankClient implements RerankClient {

    @FunctionalInterface
    protected interface ResponseSupplier {
        Response get() throws IOException;
    }

    protected record RankedChunk(int index, Float score) {
    }

    @Override
    public boolean requiresApiKey() {
        return true;
    }

    @Override
    public abstract String provider();

    @Override
    public final List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN, ModelTarget target) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        List<RetrievedChunk> dedup = new ArrayList<>(candidates.size());
        Set<String> seen = new HashSet<>();
        for (RetrievedChunk rc : candidates) {
            // 去重
            if (seen.add(rc.getId())) {
                dedup.add(rc);
            }
        }

        // 如果检索出来的数据少于 topN，无需进行重排，chat 模型可以吃到所有的 chunk
        if (topN <= 0 || dedup.size() <= topN) {
            return dedup;
        }

        return doRerank(query, dedup, topN, target);
    }

    protected JsonObject doPost(ResponseSupplier doPost, Logger log) {
        try (Response response = doPost.get()) {
            if (!response.isSuccessful()) {
                String body = HttpResponseHelper.readBody(response.body());
                log.warn("{} rerank 请求失败: status={}, body={}", provider(), response.code(), body);
                throw new ModelClientException(
                        provider() + " rerank 请求失败: HTTP " + response.code(),
                        ModelClientErrorType.fromHttpStatus(response.code()),
                        response.code()
                );
            }
            return HttpResponseHelper.parseJson(response.body(), provider());
        } catch (IOException e) {
            throw new ModelClientException(provider() + " rerank 请求失败: " + e.getMessage(), ModelClientErrorType.NETWORK_ERROR, null, e);
        }
    }

    protected JsonArray buildDocumentsArray(List<RetrievedChunk> candidates) {
        JsonArray documentsArray = new JsonArray();
        for (RetrievedChunk candidate : candidates) {
            documentsArray.add(candidate.getText() == null ? "" : candidate.getText());
        }
        return documentsArray;
    }

    protected List<RankedChunk> parseRankedChunks(JsonArray results, String scoreField) {
        if (results == null) {
            return List.of();
        }

        List<RankedChunk> ranked = new ArrayList<>(results.size());
        for (JsonElement elem : results) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject item = elem.getAsJsonObject();
            if (!item.has("index")) {
                continue;
            }

            int idx = item.get("index").getAsInt();
            Float score = null;
            if (scoreField != null && item.has(scoreField) && !item.get(scoreField).isJsonNull()) {
                score = item.get(scoreField).getAsFloat();
            }
            ranked.add(new RankedChunk(idx, score));
        }
        return ranked;
    }

    protected List<RetrievedChunk> mapRankedResults(List<RankedChunk> rankedChunks,
                                                    List<RetrievedChunk> candidates,
                                                    int topN,
                                                    boolean fillMissing) {
        List<RetrievedChunk> reranked = new ArrayList<>(Math.min(candidates.size(), topN));
        Set<String> addedIds = new HashSet<>();

        for (RankedChunk ranked : rankedChunks) {
            int idx = ranked.index();
            if (idx < 0 || idx >= candidates.size()) {
                continue;
            }

            RetrievedChunk source = candidates.get(idx);
            // 有重复则跳过
            if (!addedIds.add(source.getId())) {
                continue;
            }

            reranked.add(withScore(source, ranked.score()));
            if (reranked.size() >= topN) {
                return reranked;
            }
        }

        if (fillMissing) {
            for (RetrievedChunk candidate : candidates) {
                if (!addedIds.add(candidate.getId())) {
                    continue;
                }
                reranked.add(candidate);
                if (reranked.size() >= topN) {
                    break;
                }
            }
        }

        return reranked;
    }

    protected RetrievedChunk withScore(RetrievedChunk source, Float score) {
        if (score == null) {
            return source;
        }
        return source.toBuilder()
                .score(score)
                .build();
    }

    protected abstract List<RetrievedChunk> doRerank(String query, List<RetrievedChunk> candidates, int topN, ModelTarget target);
}
