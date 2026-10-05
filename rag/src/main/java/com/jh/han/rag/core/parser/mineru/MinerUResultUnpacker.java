
package com.jh.han.rag.core.parser.mineru;

import com.jh.han.rag.core.parser.image.ImageParseProperties;
import com.jh.han.rag.core.parser.model.AssetRef;
import com.jh.han.rag.core.parser.model.Block;
import com.jh.han.rag.core.parser.model.CodeBlock;
import com.jh.han.rag.core.parser.model.HeadingBlock;
import com.jh.han.rag.core.parser.model.HtmlTableBlock;
import com.jh.han.rag.core.parser.model.ImageBlock;
import com.jh.han.rag.core.parser.model.ListBlock;
import com.jh.han.rag.core.parser.model.ParagraphBlock;
import com.jh.han.rag.core.parser.model.ParsedDocument;
import com.jh.han.rag.core.parser.model.Provenance;
import com.jh.han.rag.framework.exception.ServiceException;
import com.jh.han.rag.infra.vlm.VlmService;
import com.jh.han.rag.rag.dto.StoredFileDTO;
import com.jh.han.rag.rag.service.FileStorageService;
import lombok.extern.slf4j.Slf4j;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableBody;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableHead;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.CustomBlock;
import org.commonmark.node.Document;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * MinerU-like 结果解包器：markdown + 图片资产 -> ParsedDocument。
 */
@Slf4j
@Component
public class MinerUResultUnpacker {

    private static final Parser MARKDOWN_PARSER = Parser.builder()
            .extensions(List.of(TablesExtension.create()))
            .build();

    private final FileStorageService fileStorageService;
    private final VlmService vlmService;
    private final ImageParseProperties imageParseProperties;

    public MinerUResultUnpacker(FileStorageService fileStorageService,
                                VlmService vlmService,
                                ImageParseProperties imageParseProperties) {
        this.fileStorageService = fileStorageService;
        this.vlmService = vlmService;
        this.imageParseProperties = imageParseProperties;
    }

    /**
     * 解包 python-services 返回的 markdown + 图片资产为 ParsedDocument。
     */
    public ParsedDocument unpackMarkdown(String markdown,
                                         List<MinerUImageAsset> images,
                                         String sourceFile,
                                         String documentId,
                                         Map<String, Object> metadata) {
        if (markdown == null || markdown.isBlank()) {
            throw new ServiceException("MinerU-like 返回 markdown 为空");
        }
        Map<String, String> imageUrlMap = uploadAssets(images, documentId);
        Map<String, String> imageDescriptionMap = describeImages(images);

        Map<String, Object> merged = new HashMap<>(metadata == null ? Map.of() : metadata);
        merged.put("parser", "MinerU");
        merged.put("imagesUploaded", imageUrlMap.size());
        merged.put("imagesDescribed", imageDescriptionMap.size());

        return parseMarkdown(markdown, sourceFile, imageUrlMap, imageDescriptionMap, merged);
    }

    private Map<String, String> uploadAssets(List<MinerUImageAsset> images, String documentId) {
        if (images == null || images.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new HashMap<>();
        for (MinerUImageAsset asset : images) {
            if (asset == null || asset.content() == null || asset.content().length == 0) {
                continue;
            }
            String key = assetKey(asset);
            String imageName = displayName(asset, key);
            String mime = asset.mimeType();
            if (mime == null || mime.isBlank()) {
                mime = inferMime(extractExt(imageName));
            }
            result.put(key, uploadSingleImage(imageName, asset.content(), mime, documentId));
        }
        return result;
    }

    private String uploadSingleImage(String imageName, byte[] data, String mime, String documentId) {
        String ext = extractExt(imageName);
        String filename = "assets/" + documentId + "/" + UUID.randomUUID() + "." + ext;
        try {
            StoredFileDTO stored = fileStorageService.uploadAsset(data, filename, mime);
            return fileStorageService.getPublicUrl(stored.getUrl());
        } catch (Exception ex) {
            log.error("MinerU 图片上传失败 imageName={}", imageName, ex);
            throw new ServiceException("MinerU 图片上传失败 " + imageName + ": " + ex.getMessage());
        }
    }

    private Map<String, String> describeImages(List<MinerUImageAsset> images) {
        if (!imageParseProperties.isEmbeddedDescribeEnabled() || images == null || images.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new HashMap<>();
        for (MinerUImageAsset asset : images) {
            if (asset == null || asset.content() == null || asset.content().length == 0) {
                continue;
            }
            String key = assetKey(asset);
            String imageName = displayName(asset, key);
            String mime = asset.mimeType();
            if (mime == null || mime.isBlank()) {
                mime = inferMime(extractExt(imageName));
            }
            try {
                String description = vlmService.describeImage(
                        asset.content(),
                        mime,
                        imageParseProperties.getDescriptionPrompt(),
                        imageParseProperties.getMaxOutputTokens()
                );
                if (description != null && !description.isBlank()) {
                    result.put(key, description.strip());
                }
            } catch (Exception ex) {
                log.warn("MinerU 内嵌图图生文失败，该图向量文本将只剩链接 imageName={}", imageName, ex);
            }
        }
        return result;
    }

    private ParsedDocument parseMarkdown(String markdown,
                                         String sourceFile,
                                         Map<String, String> imageUrlMap,
                                         Map<String, String> imageDescriptionMap,
                                         Map<String, Object> metadata) {
        Provenance prov = Provenance.ofFile(sourceFile);
        Document doc = (Document) MARKDOWN_PARSER.parse(markdown);
        UnpackVisitor visitor = new UnpackVisitor(prov, imageUrlMap, imageDescriptionMap);
        doc.accept(visitor);

        Map<String, Object> merged = new HashMap<>(metadata == null ? Map.of() : metadata);
        merged.put("blocks", visitor.getBlocks().size());
        return ParsedDocument.of(visitor.getBlocks(), merged);
    }

    private static String assetKey(MinerUImageAsset asset) {
        if (asset.markdownName() != null && !asset.markdownName().isBlank()) {
            return asset.markdownName();
        }
        if (asset.originalName() != null && !asset.originalName().isBlank()) {
            return asset.originalName();
        }
        return "image-" + Integer.toHexString(Arrays.hashCode(asset.content())) + ".bin";
    }

    private static String displayName(MinerUImageAsset asset, String fallback) {
        return asset.originalName() == null || asset.originalName().isBlank() ? fallback : asset.originalName();
    }

    private static String extractExt(String path) {
        if (path == null || path.isBlank()) {
            return "bin";
        }
        int idx = path.lastIndexOf('.');
        return idx >= 0 ? path.substring(idx + 1).toLowerCase(Locale.ROOT) : "bin";
    }

    private static String inferMime(String ext) {
        return switch (ext) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "webp" -> "image/webp";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            default -> "application/octet-stream";
        };
    }

    private static final class UnpackVisitor extends AbstractVisitor {

        private final Provenance provenance;
        private final Map<String, String> imageUrlMap;
        private final Map<String, String> imageDescriptionMap;
        private final List<Block> blocks = new ArrayList<>();

        UnpackVisitor(Provenance provenance,
                      Map<String, String> imageUrlMap,
                      Map<String, String> imageDescriptionMap) {
            this.provenance = provenance;
            this.imageUrlMap = imageUrlMap;
            this.imageDescriptionMap = imageDescriptionMap;
        }

        List<Block> getBlocks() {
            return blocks;
        }

        @Override
        public void visit(Heading heading) {
            blocks.add(new HeadingBlock(
                    provenance,
                    heading.getLevel(),
                    extractInlineText(heading)
            ));
        }

        @Override
        public void visit(Paragraph paragraph) {
            if (paragraph.getParent() instanceof ListItem) {
                return;
            }

            Node rest = paragraph.getFirstChild();
            while (rest != null) {
                if (rest instanceof Image img) {
                    handleStandaloneImage(img);
                } else if (!isBlank(rest)) {
                    break;
                }
                rest = rest.getNext();
            }

            String text = extractInlineTextFrom(rest);
            if (!text.isEmpty()) {
                blocks.add(new ParagraphBlock(provenance, text));
            }
        }

        @Override
        public void visit(FencedCodeBlock codeBlock) {
            blocks.add(new CodeBlock(
                    provenance,
                    codeBlock.getInfo(),
                    stripTrailingNewline(codeBlock.getLiteral())
            ));
        }

        @Override
        public void visit(IndentedCodeBlock codeBlock) {
            blocks.add(new CodeBlock(
                    provenance,
                    null,
                    stripTrailingNewline(codeBlock.getLiteral())
            ));
        }

        @Override
        public void visit(BulletList bulletList) {
            blocks.add(buildListBlock(bulletList, false));
        }

        @Override
        public void visit(OrderedList orderedList) {
            blocks.add(buildListBlock(orderedList, true));
        }

        @Override
        public void visit(CustomBlock customBlock) {
            if (customBlock instanceof TableBlock tableBlock) {
                handleTable(tableBlock);
                return;
            }
            super.visit(customBlock);
        }

        @Override
        public void visit(HtmlBlock htmlBlock) {
            String html = htmlBlock.getLiteral() == null ? "" : htmlBlock.getLiteral().strip();
            if (html.isEmpty()) {
                return;
            }
            blocks.add(html.regionMatches(true, 0, "<table", 0, 6)
                    ? new HtmlTableBlock(provenance, html)
                    : new ParagraphBlock(provenance, html));
        }

        private static boolean isBlank(Node node) {
            return node instanceof SoftLineBreak
                    || node instanceof HardLineBreak
                    || (node instanceof Text t && t.getLiteral().trim().isEmpty());
        }

        private void handleStandaloneImage(Image image) {
            String rawDest = image.getDestination();
            String imageKey = resolveImageKey(rawDest);
            String resolved = resolveImageUrl(rawDest, imageKey);
            String caption = extractInlineText(image);

            AssetRef asset = new AssetRef(resolved, inferMimeFromUrl(resolved));
            blocks.add(new ImageBlock(
                    provenance,
                    asset,
                    caption,
                    caption,
                    imageKey == null ? null : imageDescriptionMap.get(imageKey)
            ));
        }

        private String resolveImageUrl(String rawDest, String imageKey) {
            if (imageKey != null) {
                String uploaded = imageUrlMap.get(imageKey);
                if (uploaded != null) {
                    return uploaded;
                }
            }
            return rawDest == null ? "" : rawDest;
        }

        private String resolveImageUrl(String rawDest) {
            return resolveImageUrl(rawDest, resolveImageKey(rawDest));
        }

        private String resolveImageKey(String rawDest) {
            if (rawDest == null) {
                return null;
            }
            if (imageUrlMap.containsKey(rawDest)) {
                return rawDest;
            }
            String norm = rawDest.replaceFirst("^\\./", "");
            if (imageUrlMap.containsKey(norm)) {
                return norm;
            }
            int idx = norm.lastIndexOf('/');
            String fileName = idx >= 0 ? norm.substring(idx + 1) : norm;
            for (String key : imageUrlMap.keySet()) {
                if (key.endsWith("/" + fileName) || key.equals(fileName)) {
                    return key;
                }
            }
            return null;
        }

        private static String inferMimeFromUrl(String url) {
            String lower = url == null ? "" : url.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".png")) return "image/png";
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
            if (lower.endsWith(".webp")) return "image/webp";
            if (lower.endsWith(".gif")) return "image/gif";
            return "application/octet-stream";
        }

        private ListBlock buildListBlock(Node listNode, boolean ordered) {
            List<String> items = new ArrayList<>();
            Node child = listNode.getFirstChild();
            while (child != null) {
                if (child instanceof ListItem) {
                    items.add(extractInlineText(child).trim());
                }
                child = child.getNext();
            }
            return new ListBlock(provenance, ordered, items);
        }

        private void handleTable(TableBlock tableBlock) {
            List<String> headers = new ArrayList<>();
            List<List<String>> rows = new ArrayList<>();

            Node child = tableBlock.getFirstChild();
            while (child != null) {
                if (child instanceof TableHead head) {
                    Node hr = head.getFirstChild();
                    if (hr instanceof TableRow tr) {
                        headers.addAll(extractCellTexts(tr));
                    }
                } else if (child instanceof TableBody body) {
                    Node tr = body.getFirstChild();
                    while (tr != null) {
                        if (tr instanceof TableRow row) {
                            rows.add(extractCellTexts(row));
                        }
                        tr = tr.getNext();
                    }
                }
                child = child.getNext();
            }

            blocks.add(new com.jh.han.rag.core.parser.model.TableBlock(
                    provenance,
                    headers,
                    rows
            ));
        }

        private List<String> extractCellTexts(TableRow row) {
            List<String> cells = new ArrayList<>();
            Node cell = row.getFirstChild();
            while (cell != null) {
                if (cell instanceof TableCell tc) {
                    cells.add(extractInlineText(tc).trim());
                }
                cell = cell.getNext();
            }
            return cells;
        }

        private String extractInlineText(Node parent) {
            return extractInlineTextFrom(parent.getFirstChild());
        }

        private String extractInlineTextFrom(Node start) {
            StringBuilder sb = new StringBuilder();
            Node node = start;
            while (node != null) {
                appendInline(sb, node);
                node = node.getNext();
            }
            return sb.toString();
        }

        private void appendInline(StringBuilder sb, Node node) {
            if (node instanceof Text t) {
                sb.append(t.getLiteral());
            } else if (node instanceof Code code) {
                sb.append('`').append(code.getLiteral()).append('`');
            } else if (node instanceof Link link) {
                String inner = extractInlineText(link);
                sb.append('[').append(inner).append("](").append(link.getDestination()).append(')');
            } else if (node instanceof Image img) {
                String alt = extractInlineText(img);
                String resolved = resolveImageUrl(img.getDestination());
                sb.append("![").append(alt).append("](").append(resolved).append(')');
            } else if (node instanceof Emphasis || node instanceof StrongEmphasis) {
                sb.append(extractInlineText(node));
            } else if (node instanceof SoftLineBreak || node instanceof HardLineBreak) {
                sb.append('\n');
            } else if (node.getFirstChild() != null) {
                Node child = node.getFirstChild();
                while (child != null) {
                    appendInline(sb, child);
                    child = child.getNext();
                }
            }
        }

        private static String stripTrailingNewline(String s) {
            if (s == null) {
                return "";
            }
            return s.endsWith("\n") ? s.substring(0, s.length() - 1) : s;
        }
    }
}
