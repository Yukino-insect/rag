
package com.jh.han.rag.core.parser.mineru;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.han.rag.framework.exception.ServiceException;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * python-services/mineru_like_service HTTP 客户端
 * <p>
 * 直接调用本地 /parse/rag 接口，同步返回 markdown + 图片二进制，
 * 不再依赖第三方 SaaS 的上传票据、轮询与 zip 下载链路。
 */
@Component
public class MinerULikeServiceClient {

    private static final MediaType OCTET_STREAM = MediaType.parse("application/octet-stream");

    private final OkHttpClient baseHttpClient;
    private final ObjectMapper objectMapper;
    private final MinerUProperties properties;

    public MinerULikeServiceClient(@Qualifier("syncHttpClient") OkHttpClient baseHttpClient,
                                   ObjectMapper objectMapper,
                                   MinerUProperties properties) {
        this.baseHttpClient = baseHttpClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public MinerUTaskSubmitResponse.Data submitTask(String fileName, byte[] content, String callbackUrl, String docId) {
        if (content == null || content.length == 0) {
            throw new ServiceException("python-services 解析输入字节为空");
        }
        if (!StringUtils.hasText(callbackUrl)) {
            throw new ServiceException("MinerU callbackUrl 未配置");
        }
        if (!StringUtils.hasText(properties.getApiUrl())) {
            throw new ServiceException("MinerU-like 服务地址未配置，请设置 mineru.api-url");
        }

        RequestBody fileBody = RequestBody.create(content, OCTET_STREAM);
        MultipartBody.Builder bodyBuilder = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", fileName, fileBody)
                .addFormDataPart("callback_url", callbackUrl)
                .addFormDataPart("lang", properties.getLanguage())
                .addFormDataPart("device", properties.getDevice())
                .addFormDataPart("parse_mode", properties.getParseMode())
                .addFormDataPart("save_artifacts", "true")
                .addFormDataPart("include_raw_payload", "false")
                .addFormDataPart("use_chart_recognition", "false")
                .addFormDataPart("use_formula_recognition", Boolean.toString(properties.isEnableFormula()))
                .addFormDataPart("use_seal_recognition", "false")
                .addFormDataPart("use_table_recognition", Boolean.toString(properties.isEnableTable()));
        if (StringUtils.hasText(docId)) {
            bodyBuilder.addFormDataPart("doc_id", docId);
        }

        Request request = new Request.Builder()
                .url(resolveTaskSubmitUrl())
                .post(bodyBuilder.build())
                .build();

        OkHttpClient client = baseHttpClient.newBuilder()
                .readTimeout(Duration.ofSeconds(Math.max(5, properties.getSubmitTimeoutSeconds())))
                .writeTimeout(Duration.ofSeconds(Math.max(10, properties.getSubmitTimeoutSeconds())))
                .callTimeout(Duration.ofSeconds(Math.max(10, properties.getSubmitTimeoutSeconds() + 5L)))
                .build();

        try (Response response = client.newCall(request).execute()) {
            String body = readBody(response.body());
            if (!response.isSuccessful()) {
                throw new ServiceException("MinerU-like 任务提交失败 code=" + response.code() + " body=" + body);
            }
            MinerUTaskSubmitResponse parsed = objectMapper.readValue(body, MinerUTaskSubmitResponse.class);
            if (!parsed.success()) {
                throw new ServiceException("MinerU-like 任务提交返回失败: " + parsed.error());
            }
            if (parsed.data() == null || !StringUtils.hasText(parsed.data().taskId())) {
                throw new ServiceException("MinerU-like 任务提交返回缺少 taskId");
            }
            return parsed.data();
        } catch (IOException e) {
            throw new ServiceException("MinerU-like 任务提交网络异常: " + e.getMessage());
        }
    }

    public MinerUTaskResultResponse.Data getTaskResult(String taskId) {
        MinerUTaskResultResponse response =
                doGet(taskId, this::resolveTaskResultUrl, MinerUTaskResultResponse.class);
        if (!response.success()) {
            throw new ServiceException("MinerU-like 结果拉取返回失败: " + response.error());
        }
        if (response.data() == null) {
            throw new ServiceException("MinerU-like 结果拉取返回缺少 data");
        }
        return response.data();
    }

    public MinerUTaskStatusResponse.Data getTaskStatus(String taskId) {
        MinerUTaskStatusResponse response =
                doGet(taskId, this::resolveTaskStatusUrl, MinerUTaskStatusResponse.class);
        if (!response.success()) {
            throw new ServiceException("MinerU-like 任务状态查询失败: " + response.error());
        }
        if (response.data() == null) {
            throw new ServiceException("MinerU-Like 任务查询返回缺少 data");
        }
        return response.data();
    }

    public <T> T doGet(String taskId, Function<String, String> getUrl, Class<T> clazz) {
        if (!StringUtils.hasText(taskId)) {
            throw new ServiceException("MinerU taskId 不能为空");
        }
        if (!StringUtils.hasText(properties.getApiUrl())) {
            throw new ServiceException("MinerU-like 服务地址未配置，请设置 mineru.api-url");
        }

        Request request = new Request.Builder()
                .url(getUrl.apply(taskId))
                .get()
                .build();

        OkHttpClient client = baseHttpClient.newBuilder()
                .readTimeout(Duration.ofSeconds(Math.max(10, properties.getResultTimeoutSeconds())))
                .writeTimeout(Duration.ofSeconds(Math.max(10, properties.getResultTimeoutSeconds())))
                .callTimeout(Duration.ofSeconds(Math.max(15, properties.getResultTimeoutSeconds() + 5L)))
                .build();

        try (Response response = client.newCall(request).execute()) {
            String body = readBody(response.body());
            if (!response.isSuccessful()) {
                throw new ServiceException("MinerU-like 客户端请求失败 code=" + response.code() + " body=" + body);
            }
            return objectMapper.readValue(body, clazz);
        } catch (IOException e) {
            throw new ServiceException("MinerU-like 客户端网络异常: " + e.getMessage());
        }
    }

    public List<MinerUImageAsset> decodeAssets(List<MinerULikeServiceResponse.AssetPayload> assets) {
        if (assets == null || assets.isEmpty()) {
            return List.of();
        }
        return assets.stream()
                .map(this::decodeAsset)
                .toList();
    }

    public Map<String, Object> buildMetadata(MinerULikeServiceResponse.Data data) {
        java.util.LinkedHashMap<String, Object> metadata = new java.util.LinkedHashMap<>();
        metadata.put("requestId", data.requestId());
        metadata.put("filename", data.filename());
        metadata.put("mimeType", data.mimeType());
        if (data.summary() != null) {
            metadata.put("summary", objectMapper.convertValue(data.summary(), Map.class));
        }
        if (data.metadata() != null && !data.metadata().isEmpty()) {
            metadata.putAll(data.metadata());
        }
        return metadata;
    }

    private MinerUImageAsset decodeAsset(MinerULikeServiceResponse.AssetPayload asset) {
        if (asset == null) {
            throw new ServiceException("MinerU-like 资产节点为空");
        }
        if (!StringUtils.hasText(asset.contentBase64())) {
            throw new ServiceException("MinerU-like 资产缺少内容: " + asset.markdownName());
        }
        try {
            return new MinerUImageAsset(
                    asset.markdownName(),
                    asset.originalName(),
                    asset.mediaType(),
                    Base64.getDecoder().decode(asset.contentBase64())
            );
        } catch (IllegalArgumentException e) {
            throw new ServiceException("MinerU-like 资产 base64 非法: " + asset.markdownName());
        }
    }

    private String resolveTaskSubmitUrl() {
        String baseUrl = properties.getApiUrl();
        if (baseUrl.endsWith("/")) {
            return baseUrl + "tasks/rag";
        }
        return baseUrl + "/tasks/rag";
    }

    private String resolveTaskResultUrl(String taskId) {
        String baseUrl = properties.getApiUrl();
        if (baseUrl.endsWith("/")) {
            return baseUrl + "tasks/" + taskId + "/result";
        }
        return baseUrl + "/tasks/" + taskId + "/result";
    }

    private String resolveTaskStatusUrl(String taskId) {
        String baseUrl = properties.getApiUrl();
        if (baseUrl.endsWith("/")) {
            return baseUrl + "tasks/" + taskId + "/status";
        }
        return baseUrl + "/tasks/" + taskId + "/status";
    }

    private static String readBody(ResponseBody body) throws IOException {
        return body == null ? "" : body.string();
    }
}
