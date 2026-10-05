
package com.jh.han.rag.core.parser.mineru;

/**
 * Python MinerU-like 服务返回的图片载体
 *
 * @param markdownName markdown 中引用该图片时使用的目标名
 * @param originalName 原始文件名（用于上传资产时保留扩展名）
 * @param mimeType     图片 MIME 类型
 * @param content      图片二进制内容
 */
public record MinerUImageAsset(
        String markdownName,
        String originalName,
        String mimeType,
        byte[] content
) {
}
