
package com.jh.han.rag.core.parser.mineru;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * MinerU-like 本地解析服务配置
 * <p>
 * 配置项绑定到 application.yaml 的 {@code mineru.*} 节
 */
@Configuration
@ConfigurationProperties("mineru")
@Data
public class MinerUProperties {

    /**
     * python-services/mineru_like_service 服务根地址
     */
    private String apiUrl = "http://127.0.0.1:8091";

    /**
     * 兼容旧配置保留；本地 MinerU-like 服务默认不需要 api-key
     */
    private String apiKey;

    /**
     * 轮询 Python 任务状态的时间间隔（秒）
     */
    private int pollIntervalSeconds = 5;

    /**
     * 单任务滑动超时窗口（秒）。
     * <p>
     * 只要 Python 服务持续返回 RUNNING，就会按该窗口续期；
     * 若长时间没有进入 RUNNING / 完成态，则按该窗口判定超时。
     */
    private int timeoutSeconds = 300;

    /**
     * 单任务绝对超时上限（秒）。
     * <p>
     * 无论 RUNNING 心跳是否持续刷新，整体执行时间都不会超过该上限。
     */
    private int absoluteTimeoutSeconds = 1800;

    /**
     * 异步提交任务的超时时间（秒）
     */
    private int submitTimeoutSeconds = 30;

    /**
     * 拉取异步结果的超时时间（秒）
     */
    private int resultTimeoutSeconds = 60;

    /**
     * 是否提取表格，默认 true
     */
    private boolean enableTable = true;

    /**
     * 是否提取公式，默认 true
     */
    private boolean enableFormula = true;

    /**
     * 是否强制 OCR，默认 false(原生文本 PDF 不需要)
     * <p>
     * 字段名不带 {@code is} 前缀:Lombok 对 boolean 字段会自动加 {@code is} 前缀生成
     * getter {@link #isOcr()}，setter 为 {@link #setOcr(boolean)};
     * Spring {@code @ConfigurationProperties} 据 setter 名识别属性为 {@code ocr}，
     * 故 yaml 必须写 {@code mineru.ocr: false} 而非 {@code is-ocr}
     */
    private boolean ocr = false;

    /**
     * PaddleOCR 语言代码，默认 ch(中英文)
     */
    private String language = "ch";

    /**
     * 解析设备，默认 cpu；支持 gpu[:id]
     */
    private String device = "cpu";

    /**
     * 解析模式：structure / vl
     */
    private String parseMode = "structure";

    /**
     * 全局并发任务上限，防止打爆本地 python 服务，默认 16
     */
    private int concurrencyLimit = 16;

    /**
     * 获取 MinerU 解析许可最大等待时间（秒）
     */
    private int maxWaitSeconds = 30;

    /**
     * Python 服务回调 Java 的完整地址
     */
    private String callbackUrl = "http://127.0.0.1:9090/api/rag/internal/mineru/callback";

    /**
     * Java / Python 共用的回调 HMAC 密钥
     */
    private String callbackSecret;

    /**
     * 回调时间戳最大允许偏移（秒）
     */
    private int callbackSkewSeconds = 300;
}
