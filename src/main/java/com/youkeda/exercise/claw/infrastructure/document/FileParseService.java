package com.youkeda.exercise.claw.infrastructure.document;

import org.apache.tika.exception.TikaException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.mime.MediaType;
import org.apache.tika.mime.MimeTypes;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.ToTextContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 文件解析服务
 *
 * 基于 Apache Tika 实现文档文本提取和内嵌图片资源提取。
 * 支持格式：PDF、DOCX、XLSX、PPTX、TXT、HTML 等（由 Tika AutoDetectParser 自动识别）。
 */
@Service
public class FileParseService {

    private static final Logger log = LoggerFactory.getLogger(FileParseService.class);

    private final Parser parser = new AutoDetectParser();
    private final FileParseProperties properties;

    public FileParseService(FileParseProperties properties) {
        this.properties = properties;
    }

    /**
     * 解析文件：提取纯文本和内嵌图片
     *
     * @param fileBytes 文件字节数据
     * @param fileName  原始文件名（仅用于日志和 MIME 检测提示）
     * @return 解析结果（文本 + 内嵌图片列表），文件过大或解析失败返回 null
     */
    public FileParseResult parse(byte[] fileBytes, String fileName) {
        if (fileBytes == null || fileBytes.length == 0) {
            log.warn("文件内容为空 | fileName={}", fileName);
            return null;
        }

        // 检查文件大小限制
        if (fileBytes.length > properties.getMaxFileSize()) {
            log.warn("文件超过大小限制 | fileName={} | size={} | maxSize={}",
                    fileName, fileBytes.length, properties.getMaxFileSize());
            return null;
        }

        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName);

        List<EmbeddedImage> embeddedImages = new ArrayList<>();

        try (InputStream input = new ByteArrayInputStream(fileBytes)) {
            ToTextContentHandler handler = new ToTextContentHandler();
            ParseContext context = new ParseContext();
            context.set(Parser.class, parser);

            // 注册内嵌资源提取器：捕获 DOCX/PPTX 等 OOXML 文档中的图片
            EmbeddedDocumentExtractor extractor = new EmbeddedDocumentExtractor() {
                @Override
                public boolean shouldParseEmbedded(Metadata embeddedMetadata) {
                    String mime = embeddedMetadata.get(Metadata.CONTENT_TYPE);
                    return mime != null && mime.startsWith("image/");
                }

                @Override
                public void parseEmbedded(InputStream inputStream, org.xml.sax.ContentHandler contentHandler,
                                          Metadata embeddedMetadata, boolean outputHtml) throws IOException {
                    if (embeddedImages.size() >= properties.getMaxEmbeddedImages()) {
                        return;
                    }
                    String mime = embeddedMetadata.get(Metadata.CONTENT_TYPE);
                    String name = embeddedMetadata.get(TikaCoreProperties.RESOURCE_NAME_KEY);
                    byte[] data = inputStream.readAllBytes();
                    embeddedImages.add(new EmbeddedImage(data, mime, name));
                    log.debug("提取内嵌图片 | name={} | mime={} | size={}", name, mime, data.length);
                }
            };
            context.set(EmbeddedDocumentExtractor.class, extractor);

            // 执行解析
            parser.parse(input, handler, metadata, context);
            String text = handler.toString();

            log.info("文件解析成功 | fileName={} | textLength={} | embeddedImages={}",
                    fileName, text.length(), embeddedImages.size());

            // 按配置截断文本
            if (text.length() > properties.getMaxTextLength()) {
                text = text.substring(0, properties.getMaxTextLength())
                        + "\n\n...[文件内容过长，已截断至 " + properties.getMaxTextLength() + " 字符]";
            }

            return new FileParseResult(text, Collections.unmodifiableList(embeddedImages));

        } catch (TikaException e) {
            log.warn("Tika 解析失败，可能是不支持的格式 | fileName={} | error={}", fileName, e.getMessage());
            return null;
        } catch (SAXException | IOException e) {
            log.error("文件解析异常 | fileName={} | error={}", fileName, e.getMessage());
            return null;
        }
    }

    /**
     * 检测文件字节的 MIME 类型（基于 magic bytes，不依赖扩展名）
     *
     * @param fileBytes 文件字节数据
     * @return MIME 类型字符串，如 "image/jpeg"、"application/pdf"，检测失败返回 "application/octet-stream"
     */
    public String detectMimeType(byte[] fileBytes) {
        if (fileBytes == null || fileBytes.length == 0) {
            return "application/octet-stream";
        }
        try {
            MimeTypes mimeTypes = MimeTypes.getDefaultMimeTypes();
            MediaType mediaType = mimeTypes.detect(new ByteArrayInputStream(fileBytes), new Metadata());
            return mediaType.toString();
        } catch (Exception e) {
            log.warn("MIME 类型检测异常 | error={}", e.getMessage());
            return "application/octet-stream";
        }
    }

    /**
     * 文件解析结果
     *
     * @param text   提取的纯文本内容
     * @param images 内嵌图片列表（OOXML 文档中嵌入的图片资源）
     */
    public record FileParseResult(String text, List<EmbeddedImage> images) {}

    /**
     * 内嵌图片
     *
     * @param data     图片字节数据
     * @param mimeType 图片 MIME 类型（如 "image/png"）
     * @param fileName 图片文件名（如 "image1.png"）
     */
    public record EmbeddedImage(byte[] data, String mimeType, String fileName) {}
}
