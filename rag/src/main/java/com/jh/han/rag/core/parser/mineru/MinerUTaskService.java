
package com.jh.han.rag.core.parser.mineru;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.han.rag.core.parser.ParserType;
import com.jh.han.rag.core.parser.model.ParsedDocument;
import com.jh.han.rag.framework.context.UserContext;
import com.jh.han.rag.framework.exception.ClientException;
import com.jh.han.rag.knowledge.dao.entity.KnowledgeDocumentDO;
import com.jh.han.rag.knowledge.dao.entity.KnowledgeDocumentMineruTaskDO;
import com.jh.han.rag.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.jh.han.rag.knowledge.dao.mapper.KnowledgeDocumentMineruTaskMapper;
import com.jh.han.rag.knowledge.enums.KnowledgeDocumentMineruTaskStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Date;

@Slf4j
@Service
@RequiredArgsConstructor
public class MinerUTaskService {

    private final MinerULikeServiceClient minerULikeServiceClient;
    private final MinerUResultUnpacker minerUResultUnpacker;
    private final MinerUProperties minerUProperties;
    private final KnowledgeDocumentMineruTaskMapper mineruTaskMapper;
    private final ObjectMapper objectMapper;
    private final KnowledgeDocumentMapper knowledgeDocumentMapper;

    public MinerUTaskSubmitResponse.Data submitMineruAsyncTask(String documentId,
                                       byte[] fileBytes,
                                       String docName) {
        if (!StringUtils.hasText(minerUProperties.getCallbackUrl())) {
            throw new ClientException("MinerU 回调地址未配置，请设置 mineru.callback-url");
        }
        if (!StringUtils.hasText(minerUProperties.getCallbackSecret())) {
            throw new ClientException("MinerU 回调密钥未配置，请设置 mineru.callback-secret");
        }

        MinerUTaskSubmitResponse.Data submit = minerULikeServiceClient.submitTask(
                docName,
                fileBytes,
                minerUProperties.getCallbackUrl(),
                documentId
        );
        KnowledgeDocumentMineruTaskDO task = KnowledgeDocumentMineruTaskDO.builder()
                .docId(documentId)
                .mineruTaskId(submit.taskId())
                .status(KnowledgeDocumentMineruTaskStatus.SUBMITTED.getCode())
                .operator(resolveOperator())
                .submittedAt(new Date())
                .build();
        mineruTaskMapper.insert(task);

        log.info("文档分块已提交 MinerU 异步任务，docId={}, mineruTaskId={}",
                documentId, submit.taskId());

        return submit;
    }

    public KnowledgeDocumentMineruTaskDO findMineruTaskByTaskId(String taskId) {
        return mineruTaskMapper.selectOne(
                Wrappers.lambdaQuery(KnowledgeDocumentMineruTaskDO.class)
                        .eq(KnowledgeDocumentMineruTaskDO::getMineruTaskId, taskId)
                        .last("LIMIT 1")
        );
    }

    public void handleMineruCallback(String rawBody, String timestamp, String signature) {
        // 验证签名
        verifyMineruCallbackSignature(rawBody, timestamp, signature);

        // 将原始字符串转换成 Java 对象
        MinerUTaskCallbackRequest callback;
        try {
            callback = objectMapper.readValue(rawBody, MinerUTaskCallbackRequest.class);
        } catch (Exception e) {
            throw new ClientException("MinerU 回调请求体解析失败: " + e.getMessage());
        }

        if (callback == null || !StringUtils.hasText(callback.taskId())) {
            throw new ClientException("MinerU 回调缺少 taskId");
        }

        KnowledgeDocumentMineruTaskDO task = findMineruTaskByTaskId(callback.taskId());
        if (task == null) {
            log.warn("收到未知 MinerU 回调，忽略。taskId={}, docId={}", callback.taskId(), callback.docId());
            return;
        }

        String status = callback.status();

        // python 端解析失败
        if (KnowledgeDocumentMineruTaskStatus.FAILED.getCode().equalsIgnoreCase(status)) {
            handleMineruFailureCallback(task, callback);
            return;
        }

        // python 端传来非法回调状态
        if (!KnowledgeDocumentMineruTaskStatus.SUCCESS.getCode().equalsIgnoreCase(status)) {
            throw new ClientException("不支持的 MinerU 回调状态: " + callback.status());
        }

        KnowledgeDocumentMineruTaskDO latest = mineruTaskMapper.selectById(task.getId());
        if (latest == null) {
            log.warn("MinerU 回调对应任务已不存在，忽略。taskId={}", callback.taskId());
            return;
        }

        if (KnowledgeDocumentMineruTaskStatus.SUCCESS.getCode().equals(latest.getStatus())
                || KnowledgeDocumentMineruTaskStatus.FAILED.getCode().equals(latest.getStatus())) {
            log.info("MinerU 回调重复或任务已在处理中，忽略。taskId={}, status={}", callback.taskId(), latest.getStatus());
            return;
        }

        Date now = new Date();
        mineruTaskMapper.update(
                null,
                Wrappers.lambdaUpdate(KnowledgeDocumentMineruTaskDO.class)
                        .set(KnowledgeDocumentMineruTaskDO::getStatus, KnowledgeDocumentMineruTaskStatus.CALLBACK_RECEIVED.getCode())
                        .set(KnowledgeDocumentMineruTaskDO::getRequestId, StrUtil.trimToNull(callback.requestId()))
                        .set(KnowledgeDocumentMineruTaskDO::getParseDuration, callback.durationMs())
                        .set(KnowledgeDocumentMineruTaskDO::getErrorMessage, null)
                        .set(KnowledgeDocumentMineruTaskDO::getCallbackAt, now)
                        .eq(KnowledgeDocumentMineruTaskDO::getId, task.getId())
                        .in(KnowledgeDocumentMineruTaskDO::getStatus,
                                KnowledgeDocumentMineruTaskStatus.SUBMITTED.getCode(),
                                KnowledgeDocumentMineruTaskStatus.CALLBACK_RECEIVED.getCode())
        );
    }

    public ParsedDocument getTaskResult(String taskId) {
        MinerUTaskResultResponse.Data taskResult = minerULikeServiceClient.getTaskResult(taskId);
        String docId = taskResult.docId();
        KnowledgeDocumentDO documentDO = getDocumentById(docId);
        MinerULikeServiceResponse.Data result = taskResult.result();
        return buildParsedDocumentFromMineruResponse(documentDO, result);
    }

    public KnowledgeDocumentMineruTaskStatus getTaskStatus(String taskId) {
        MinerUTaskStatusResponse.Data taskData = minerULikeServiceClient.getTaskStatus(taskId);
        return KnowledgeDocumentMineruTaskStatus.from(taskData.status());
    }

    private KnowledgeDocumentDO getDocumentById(String docId) {
        LambdaQueryWrapper<KnowledgeDocumentDO> query = Wrappers.lambdaQuery(KnowledgeDocumentDO.class)
                .eq(KnowledgeDocumentDO::getId, docId);
        return knowledgeDocumentMapper.selectOne(query);
    }

    private ParsedDocument buildParsedDocumentFromMineruResponse(KnowledgeDocumentDO documentDO,
                                                                MinerULikeServiceResponse.Data response) {
        ParsedDocument parsed = minerUResultUnpacker.unpackMarkdown(
                response.markdown(),
                minerULikeServiceClient.decodeAssets(response.assets()),
                documentDO.getDocName(),
                documentDO.getId(),
                minerULikeServiceClient.buildMetadata(response)
        );

        Map<String, Object> mergedMeta = new HashMap<>(parsed.metadata() == null ? Map.of() : parsed.metadata());
        mergedMeta.put("minerU.batchId", response.requestId());
        mergedMeta.put("minerU.requestId", response.requestId());
        if (response.metadata() != null) {
            Object outputDir = response.metadata().get("request_output_dir");
            if (outputDir != null) {
                mergedMeta.put("minerU.outputDir", outputDir.toString());
            }
        }
        mergedMeta.put("parser", ParserType.MINERU.getType());
        mergedMeta.put("mimeType", response.mimeType() == null || response.mimeType().isBlank()
                ? documentDO.getFileType()
                : response.mimeType());
        if (response.summary() != null && response.summary().pageCount() != null) {
            mergedMeta.put("pageCount", response.summary().pageCount());
        }
        return ParsedDocument.of(parsed.blocks(), mergedMeta);
    }

    private void handleMineruFailureCallback(KnowledgeDocumentMineruTaskDO task, MinerUTaskCallbackRequest callback) {
        KnowledgeDocumentMineruTaskDO latest = mineruTaskMapper.selectById(task.getId());
        if (latest == null) {
            return;
        }

        if (KnowledgeDocumentMineruTaskStatus.SUCCESS.getCode().equalsIgnoreCase(latest.getStatus())
                || KnowledgeDocumentMineruTaskStatus.FAILED.getCode().equalsIgnoreCase(latest.getStatus())) {
            return;
        }

        Date now = new Date();
        mineruTaskMapper.update(
                null,
                Wrappers.lambdaUpdate(KnowledgeDocumentMineruTaskDO.class)
                        .set(KnowledgeDocumentMineruTaskDO::getStatus, KnowledgeDocumentMineruTaskStatus.FAILED.getCode())
                        .set(KnowledgeDocumentMineruTaskDO::getRequestId, StrUtil.trimToNull(callback.requestId()))
                        .set(KnowledgeDocumentMineruTaskDO::getParseDuration, callback.durationMs())
                        .set(KnowledgeDocumentMineruTaskDO::getErrorMessage, callback.error())
                        .set(KnowledgeDocumentMineruTaskDO::getCallbackAt, now)
                        .set(KnowledgeDocumentMineruTaskDO::getCompletedAt, now)
                        .eq(KnowledgeDocumentMineruTaskDO::getId, task.getId())
                        .in(KnowledgeDocumentMineruTaskDO::getStatus,
                                KnowledgeDocumentMineruTaskStatus.SUBMITTED.getCode(),
                                KnowledgeDocumentMineruTaskStatus.CALLBACK_RECEIVED.getCode())
        );
    }

    private void verifyMineruCallbackSignature(String rawBody, String timestamp, String signature) {
        if (!StringUtils.hasText(timestamp) || !StringUtils.hasText(signature)) {
            throw new ClientException("MinerU 回调缺少签名头");
        }
        if (!StringUtils.hasText(minerUProperties.getCallbackSecret())) {
            throw new ClientException("MinerU 回调密钥未配置");
        }

        long ts;
        try {
            ts = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            throw new ClientException("MinerU 回调时间戳非法");
        }
        long skew = Math.abs(Instant.now().getEpochSecond() - ts);
        if (skew > Math.max(30, minerUProperties.getCallbackSkewSeconds())) {
            throw new ClientException("MinerU 回调已过期");
        }

        String payload = timestamp.trim() + "\n" + rawBody;
        String expected = signHmacSha256(payload, minerUProperties.getCallbackSecret());
        if (!MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signature.trim().toLowerCase().getBytes(StandardCharsets.UTF_8))) {
            throw new ClientException("MinerU 回调验签失败");
        }
    }

    private static String signHmacSha256(String payload, String secret) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("计算 MinerU HMAC 失败", e);
        }
    }

    private String resolveOperator() {
        return StringUtils.hasText(UserContext.getUsername()) ? UserContext.getUsername() : "system";
    }

    public void markFailed(String taskId, String errMsg) {
        LambdaUpdateWrapper<KnowledgeDocumentMineruTaskDO> update = Wrappers.lambdaUpdate(KnowledgeDocumentMineruTaskDO.class)
                .eq(KnowledgeDocumentMineruTaskDO::getMineruTaskId, taskId)
                .set(KnowledgeDocumentMineruTaskDO::getStatus, KnowledgeDocumentMineruTaskStatus.FAILED.getCode())
                .set(KnowledgeDocumentMineruTaskDO::getErrorMessage, errMsg)
                .set(KnowledgeDocumentMineruTaskDO::getCompletedAt, new Date());
        mineruTaskMapper.update(update);
    }
}
