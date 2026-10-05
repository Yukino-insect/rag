
package com.jh.han.rag.core.parser.mineru;

import com.jh.han.rag.core.parser.DocumentParser;
import com.jh.han.rag.core.parser.ParserType;
import com.jh.han.rag.core.parser.model.ParsedDocument;
import com.jh.han.rag.core.parser.registry.ParseProfile;
import com.jh.han.rag.framework.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.Semaphore;

/**
 * MinerU-like 本地文档解析器（PDF / Word / PPT / Excel）。
 * <p>
 * 文件提交到 python-services/mineru_like_service 后，通过回调表 + 共享轮询器等待解析完成。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MinerUDocumentParser implements DocumentParser {

    /**
     * 版面解析类：PDF / Word / PPT 只有 MinerU 一条路，两个档位都由它承担。
     */
    private static final Set<String> LAYOUT_MIME_TYPES = Set.of(
            "application/pdf",
            "application/x-pdf",
            "application/msword",
            "application/vnd.ms-word",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/vnd.openxmlformats-officedocument.presentationml.slideshow"
    );

    /**
     * 表格类只在保真档认领：默认走 POI 快速 key-val，用户选保真档才付 MinerU 的成本。
     */
    private static final Set<String> SPREADSHEET_MIME_TYPES = Set.of(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-excel"
    );

    /**
     * options 字段：文件名，写入 Provenance.sourceFile。
     */
    public static final String OPT_SOURCE_FILE = "sourceFile";

    /**
     * options 字段：文档 ID，用于任务表和资产 key 命名。
     */
    public static final String OPT_DOCUMENT_ID = "documentId";

    private final MinerUTaskService minerUTaskService;
    private final MinerUPollingExecutor pollingExecutor;
    private final MinerUProperties properties;
    private final Semaphore mineruParserSemaphore;

    @Override
    public String getParserType() {
        return ParserType.MINERU.getType();
    }

    @Override
    public Map<ParseProfile, Set<String>> supportedMimeTypes() {
        return Map.of(
                ParseProfile.FAST, LAYOUT_MIME_TYPES,
                ParseProfile.FIDELITY, SPREADSHEET_MIME_TYPES
        );
    }

    @Override
    public ParsedDocument parseStructured(byte[] content, String mimeType, Map<String, Object> options) {
        if (content == null || content.length == 0) {
            throw new ServiceException("MinerU 解析输入字节为空");
        }

        boolean acquired = false;
        try {
            acquired = mineruParserSemaphore.tryAcquire(
                    properties.getMaxWaitSeconds(),
                    TimeUnit.SECONDS
            );
            if (!acquired) {
                throw new ServiceException("MinerU 解析任务过多，请稍后重试");
            }
            return doParseStructured(content, options);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException("MinerU 获取解析许可被中断");
        } finally {
            if (acquired) {
                mineruParserSemaphore.release();
            }
        }
    }

    private ParsedDocument doParseStructured(byte[] content, Map<String, Object> options) {
        String sourceFile = extractString(options, OPT_SOURCE_FILE, "");
        String documentId = extractString(options, OPT_DOCUMENT_ID, "");

        MinerUTaskSubmitResponse.Data submitted =
                minerUTaskService.submitMineruAsyncTask(documentId, content, sourceFile);
        log.info("MinerU 源文件上传完毕 documentId={} taskId={}", documentId, submitted.taskId());

        try {
            pollingExecutor
                    .submitAndAwait(
                            submitted.taskId(),
                            Duration.ofSeconds(properties.getTimeoutSeconds()),
                            Duration.ofSeconds(properties.getAbsoluteTimeoutSeconds())
                    )
                    .get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException("MinerU 等待被中断 taskId=" + submitted.taskId());
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof TimeoutException te) {
                throw new ServiceException(te.getMessage());
            }
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new ServiceException("MinerU 等待异常 taskId=" + submitted.taskId() + ": " + cause.getMessage());
        }

        return minerUTaskService.getTaskResult(submitted.taskId());
    }

    private static String extractString(Map<String, Object> options, String key, String defaultValue) {
        if (options == null) {
            return defaultValue;
        }
        Object v = options.get(key);
        return (v == null || v.toString().isBlank()) ? defaultValue : v.toString();
    }
}
