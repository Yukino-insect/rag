
package com.jh.han.rag.core.parser.mineru;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * python-services/mineru_like_service 的 Rag 专用响应体
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MinerULikeServiceResponse(
        boolean success,
        Data data,
        String error
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Data(
            @JsonProperty("request_id")
            String requestId,
            String filename,
            @JsonProperty("mime_type")
            String mimeType,
            Summary summary,
            String markdown,
            List<AssetPayload> assets,
            Map<String, Object> metadata
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Summary(
            String parser,
            @JsonProperty("parse_mode")
            String parseMode,
            String lang,
            String device,
            @JsonProperty("page_count")
            Integer pageCount,
            @JsonProperty("converted_from")
            String convertedFrom,
            @JsonProperty("stored_source_path")
            String storedSourcePath,
            @JsonProperty("normalized_source_path")
            String normalizedSourcePath,
            @JsonProperty("combined_markdown_path")
            String combinedMarkdownPath
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AssetPayload(
            @JsonProperty("markdown_name")
            String markdownName,
            @JsonProperty("original_name")
            String originalName,
            @JsonProperty("media_type")
            String mediaType,
            @JsonProperty("content_base64")
            String contentBase64,
            @JsonProperty("page_index")
            Integer pageIndex
    ) {
    }
}
