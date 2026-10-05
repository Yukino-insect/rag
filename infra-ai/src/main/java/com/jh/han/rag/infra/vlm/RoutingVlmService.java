
package com.jh.han.rag.infra.vlm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jh.han.rag.framework.trace.RagTraceNode;
import com.jh.han.rag.infra.config.AIModelProperties;
import com.jh.han.rag.infra.enums.ModelCapability;
import com.jh.han.rag.infra.enums.ModelProvider;
import com.jh.han.rag.infra.http.HttpMediaTypes;
import com.jh.han.rag.infra.http.HttpResponseHelper;
import com.jh.han.rag.infra.http.ModelClientErrorType;
import com.jh.han.rag.infra.http.ModelClientException;
import com.jh.han.rag.infra.http.ModelUrlResolver;
import com.jh.han.rag.infra.model.ModelSelector;
import com.jh.han.rag.infra.model.ModelTarget;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Base64;
import java.util.List;

/**
 * 路由式 VLM 服务实现类
 * <p>
 * 复用 chat 链路同款基础设施：provider 配置、URL 解析、错误体系、OkHttp 同步客户端
 * 与 chat 的唯一差异是请求体 messages[].content 为多模态数组（text + image_url）
 * 入库期单次同步调用，从 ai.vlm 组取首个可用候选即可，无需 fallback
 */
@Slf4j
@Service
@Primary
public class RoutingVlmService implements VlmService {

    private static final String LABEL = "vlm";

    private final ModelSelector selector;
    private final OkHttpClient syncHttpClient;

    public RoutingVlmService(ModelSelector selector,
                             @Qualifier("syncHttpClient") OkHttpClient syncHttpClient) {
        this.selector = selector;
        this.syncHttpClient = syncHttpClient;
    }

    @Override
    @RagTraceNode(name = "vlm-describe", type = "VLM")
    public String describeImage(byte[] imageBytes, String mime, String prompt, Integer maxOutputTokens) {
        ModelTarget target = resolveTarget();
        AIModelProperties.ProviderConfig provider = HttpResponseHelper.requireProvider(target, LABEL);
        String url = ModelUrlResolver.resolveUrl(provider, target.candidate(), ModelCapability.CHAT);
        VlmPlatform platform = VlmPlatform.resolve(target, url);
        if (platform.requiresApiKey()) {
            HttpResponseHelper.requireApiKey(provider, LABEL);
        }

        Request request = platform.buildRequest(url, provider, target, this, prompt, imageBytes, mime, maxOutputTokens);
        JsonObject respJson = execute(request);
        return platform.extractContent(this, respJson);
    }

    private ModelTarget resolveTarget() {
        List<ModelTarget> targets = selector.selectVlmCandidates();
        if (targets.isEmpty()) {
            throw new ModelClientException("VLM 模型不可用，请检查 ai.vlm 配置",
                    ModelClientErrorType.PROVIDER_ERROR, null);
        }
        return targets.get(0);
    }

    private JsonObject execute(Request request) {
        try (Response response = syncHttpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String body = HttpResponseHelper.readBody(response.body());
                log.warn("VLM 请求失败: status={}, body={}", response.code(), body);
                throw new ModelClientException(
                        "VLM 请求失败: HTTP " + response.code(),
                        ModelClientErrorType.fromHttpStatus(response.code()),
                        response.code());
            }
            return HttpResponseHelper.parseJson(response.body(), LABEL);
        } catch (IOException e) {
            throw new ModelClientException(
                    "VLM 请求失败: " + e.getMessage(),
                    ModelClientErrorType.NETWORK_ERROR, null, e);
        }
    }

    private JsonObject buildOpenAiCompatibleBody(ModelTarget target, String prompt, byte[] image, String mime, Integer maxOutputTokens) {
        String dataUrl = "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(image);

        JsonObject textPart = new JsonObject();
        textPart.addProperty("type", "text");
        textPart.addProperty("text", prompt);

        JsonObject imageUrl = new JsonObject();
        imageUrl.addProperty("url", dataUrl);
        JsonObject imagePart = new JsonObject();
        imagePart.addProperty("type", "image_url");
        imagePart.add("image_url", imageUrl);

        JsonArray content = new JsonArray();
        content.add(textPart);
        content.add(imagePart);

        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.add("content", content);

        JsonArray messages = new JsonArray();
        messages.add(userMsg);

        JsonObject body = new JsonObject();
        body.addProperty("model", HttpResponseHelper.requireModel(target, LABEL));
        body.add("messages", messages);
        if (maxOutputTokens != null && maxOutputTokens > 0) {
            body.addProperty("max_tokens", maxOutputTokens);
        }
        return body;
    }

    private JsonObject buildOllamaNativeBody(ModelTarget target, String prompt, byte[] image, Integer maxOutputTokens) {
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.addProperty("content", prompt);

        JsonArray images = new JsonArray();
        images.add(Base64.getEncoder().encodeToString(image));
        message.add("images", images);

        JsonArray messages = new JsonArray();
        messages.add(message);

        JsonObject body = new JsonObject();
        body.addProperty("model", HttpResponseHelper.requireModel(target, LABEL));
        body.addProperty("stream", false);
        body.add("messages", messages);
        if (maxOutputTokens != null && maxOutputTokens > 0) {
            JsonObject options = new JsonObject();
            options.addProperty("num_predict", maxOutputTokens);
            body.add("options", options);
        }
        return body;
    }

    private String extractOpenAiCompatibleContent(JsonObject respJson) {
        if (respJson == null || !respJson.has("choices")) {
            throw new ModelClientException("VLM 响应缺少 choices", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        JsonArray choices = respJson.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new ModelClientException("VLM 响应 choices 为空", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        JsonObject choice0 = choices.get(0).getAsJsonObject();
        if (choice0 == null || !choice0.has("message")) {
            throw new ModelClientException("VLM 响应缺少 message", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        JsonObject message = choice0.getAsJsonObject("message");
        return extractMessageContent(message, "VLM 响应缺少 content");
    }

    private String extractOllamaNativeContent(JsonObject respJson) {
        if (respJson == null || !respJson.has("message")) {
            throw new ModelClientException("VLM 响应缺少 message", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        JsonObject message = respJson.getAsJsonObject("message");
        return extractMessageContent(message, "VLM 响应缺少 content");
    }

    private String extractMessageContent(JsonObject message, String missingMessage) {
        if (message == null || !message.has("content") || message.get("content").isJsonNull()) {
            throw new ModelClientException(missingMessage, ModelClientErrorType.INVALID_RESPONSE, null);
        }
        return readContentText(message.get("content"));
    }

    private String readContentText(JsonElement content) {
        if (content == null || content.isJsonNull()) {
            throw new ModelClientException("VLM 响应缺少 content", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        if (content.isJsonPrimitive()) {
            return content.getAsString();
        }
        if (content.isJsonArray()) {
            StringBuilder builder = new StringBuilder();
            for (JsonElement item : content.getAsJsonArray()) {
                if (!item.isJsonObject()) {
                    continue;
                }
                JsonObject part = item.getAsJsonObject();
                if (part.has("text") && !part.get("text").isJsonNull()) {
                    if (!builder.isEmpty()) {
                        builder.append('\n');
                    }
                    builder.append(part.get("text").getAsString());
                }
            }
            String text = builder.toString().trim();
            if (!text.isEmpty()) {
                return text;
            }
        }
        throw new ModelClientException("VLM 响应 content 格式不支持", ModelClientErrorType.INVALID_RESPONSE, null);
    }

    /**
     * 使用枚举实现的策略模式
     */
    private enum VlmPlatform {
        OPENAI_COMPATIBLE {
            @Override
            Request buildRequest(String url,
                                 AIModelProperties.ProviderConfig provider,
                                 ModelTarget target,
                                 RoutingVlmService service,
                                 String prompt,
                                 byte[] imageBytes,
                                 String mime,
                                 Integer maxOutputTokens) {
                JsonObject reqBody = service.buildOpenAiCompatibleBody(target, prompt, imageBytes, mime, maxOutputTokens);
                return new Request.Builder()
                        .url(url)
                        .addHeader("Authorization", "Bearer " + provider.getApiKey())
                        .post(RequestBody.create(reqBody.toString(), HttpMediaTypes.JSON))
                        .build();
            }

            @Override
            String extractContent(RoutingVlmService service, JsonObject respJson) {
                return service.extractOpenAiCompatibleContent(respJson);
            }
        },
        OLLAMA_OPENAI_COMPATIBLE {
            @Override
            boolean requiresApiKey() {
                return false;
            }

            @Override
            Request buildRequest(String url,
                                 AIModelProperties.ProviderConfig provider,
                                 ModelTarget target,
                                 RoutingVlmService service,
                                 String prompt,
                                 byte[] imageBytes,
                                 String mime,
                                 Integer maxOutputTokens) {
                JsonObject reqBody = service.buildOpenAiCompatibleBody(target, prompt, imageBytes, mime, maxOutputTokens);
                return new Request.Builder()
                        .url(url)
                        .post(RequestBody.create(reqBody.toString(), HttpMediaTypes.JSON))
                        .build();
            }

            @Override
            String extractContent(RoutingVlmService service, JsonObject respJson) {
                return service.extractOpenAiCompatibleContent(respJson);
            }
        },
        OLLAMA_NATIVE {
            @Override
            boolean requiresApiKey() {
                return false;
            }

            @Override
            Request buildRequest(String url,
                                 AIModelProperties.ProviderConfig provider,
                                 ModelTarget target,
                                 RoutingVlmService service,
                                 String prompt,
                                 byte[] imageBytes,
                                 String mime,
                                 Integer maxOutputTokens) {
                JsonObject reqBody = service.buildOllamaNativeBody(target, prompt, imageBytes, maxOutputTokens);
                return new Request.Builder()
                        .url(url)
                        .post(RequestBody.create(reqBody.toString(), HttpMediaTypes.JSON))
                        .build();
            }

            @Override
            String extractContent(RoutingVlmService service, JsonObject respJson) {
                return service.extractOllamaNativeContent(respJson);
            }
        };

        boolean requiresApiKey() {
            return true;
        }

        abstract Request buildRequest(String url,
                                      AIModelProperties.ProviderConfig provider,
                                      ModelTarget target,
                                      RoutingVlmService service,
                                      String prompt,
                                      byte[] imageBytes,
                                      String mime,
                                      Integer maxOutputTokens);

        abstract String extractContent(RoutingVlmService service, JsonObject respJson);

        static VlmPlatform resolve(ModelTarget target, String url) {
            if (!ModelProvider.OLLAMA.matches(target.candidate().getProvider())) {
                return OPENAI_COMPATIBLE;
            }
            return url != null && url.contains("/api/chat") ? OLLAMA_NATIVE : OLLAMA_OPENAI_COMPATIBLE;
        }
    }
}
