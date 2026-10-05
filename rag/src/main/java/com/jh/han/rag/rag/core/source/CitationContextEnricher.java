
package com.jh.han.rag.rag.core.source;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.jh.han.rag.framework.convention.SourceRef;
import com.jh.han.rag.rag.config.RAGConfigProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 为已格式化的知识库上下文注入请求级引用编号
 * <p>
 * 上下文格式化阶段只写入内部 {@code data-rag-doc-id}，来源装配完成后再依据
 * {@link SourceRef#getIndex()} 替换为模型可见的 {@code ref}。这样 Prompt、SSE、落库和前端
 * 始终复用同一份来源编号，同时不把内部文档 ID 暴露给模型
 * <p>
 * 引用开关关闭时按「无来源」处理：只抹掉内部锚点、不注入编号。无论开关如何，
 * 上下文都必须先过这一道，内部 docId 才不会漏进模型可见文本
 */
@Component
@RequiredArgsConstructor
public class CitationContextEnricher {

    private static final Pattern CONTENT_TAG = Pattern.compile(
            "(?m)^<content([^>]*) data-rag-doc-id=\"([^\"]*)\">$");

    private final RAGConfigProperties ragConfigProperties;

    public String enrich(String kbContext, List<SourceRef> sources) {
        if (StrUtil.isBlank(kbContext)) {
            return StrUtil.emptyIfNull(kbContext);
        }

        Map<String, Integer> indexByDocId = Boolean.TRUE.equals(ragConfigProperties.getCitationEnabled())
                ? indexByDocId(sources)
                : Map.of();
        Matcher matcher = CONTENT_TAG.matcher(kbContext);
        StringBuilder result = new StringBuilder(kbContext.length());
        while (matcher.find()) {
            String attributes = matcher.group(1);
            String docId = matcher.group(2);
            Integer index = indexByDocId.get(docId);
            String replacement = index == null
                    ? "<content" + attributes + ">"
                    : "<content" + attributes + " ref=\"" + index + "\">";
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private Map<String, Integer> indexByDocId(List<SourceRef> sources) {
        if (CollUtil.isEmpty(sources)) {
            return Map.of();
        }
        Map<String, Integer> result = new LinkedHashMap<>();
        for (SourceRef source : sources) {
            if (source == null
                    || StrUtil.isBlank(source.getDocId())
                    || source.getIndex() == null) {
                continue;
            }
            result.putIfAbsent(source.getDocId(), source.getIndex());
        }
        return result;
    }
}
