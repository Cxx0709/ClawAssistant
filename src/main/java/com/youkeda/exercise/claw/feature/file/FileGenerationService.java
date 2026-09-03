package com.youkeda.exercise.claw.feature.file;

import com.youkeda.exercise.claw.ai.llm.LLMClient;
import com.youkeda.exercise.claw.agent.memory.ContextStore;
import com.youkeda.exercise.claw.agent.memory.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.apache.fontbox.ttf.TrueTypeCollection;
import org.apache.fontbox.ttf.TrueTypeFont;

/**
 * 文件生成服务
 *
 * 根据用户需求生成 PDF 或 Word 文档文件。
 * 流程：格式检测 → LLM 生成文档内容 → PDFBox/POI 渲染 → 返回字节
 *
 * PDF 依赖 PDFBox，DOCX 依赖 Apache POI（均由 tika-parsers-standard-package 间接引入）
 */
@Service
public class FileGenerationService {

    private static final Logger log = LoggerFactory.getLogger(FileGenerationService.class);

    /** PDF 页面参数 */
    private static final float PAGE_WIDTH = PDRectangle.A4.getWidth();
    private static final float PAGE_HEIGHT = PDRectangle.A4.getHeight();
    private static final float MARGIN = 50;
    private static final float PAGE_CONTENT_WIDTH = PAGE_WIDTH - 2 * MARGIN;
    private static final float LEADING = 18;

    /** LLM 内容生成提示词：要求 LLM 以 Markdown 结构输出 */
    private static final String CONTENT_GENERATION_PROMPT =
            "你是一个文档生成助手。结合对话历史，根据用户的需求生成结构化的文档内容。\n" +
            "请使用 Markdown 格式输出，包含：\n" +
            "- # 一级标题（文档标题）\n" +
            "- ## 二级标题（章节标题）\n" +
            "- ### 三级标题（子章节标题）\n" +
            "- - 无序列表项\n" +
            "- 1. 2. 有序列表项\n" +
            "- 普通段落文本（段落之间空行分隔）\n" +
            "\n" +
            "输出的内容应当完整、详细、格式清晰。\n" +
            "直接输出文档内容，不要任何解释。";

    private final LLMClient llmClient;
    private final ContextStore contextStore;

    public FileGenerationService(LLMClient llmClient, ContextStore contextStore) {
        this.llmClient = llmClient;
        this.contextStore = contextStore;
    }

    /**
     * 生成文件
     *
     * @param userText 用户请求文本
     * @return 文件生成结果（字节、文件名、描述），失败返回 null
     */
    public FileGenerationResult generate(String userText) {
        return generate(userText, null);
    }

    /**
     * 生成文件（带格式提示）
     *
     * @param userText    用户请求文本
     * @param formatHint  格式提示（"pdf"、"docx" 或 "md"），不为 null 时优先使用此格式
     * @return 文件生成结果（字节、文件名、描述），失败返回 null
     */
    public FileGenerationResult generate(String userText, String formatHint) {
        log.info("FileGenerationService 开始生成 | text={} | formatHint={}", userText, formatHint);

        // 1. 检测目标格式（优先使用 formatHint）
        FileFormat format;
        if (formatHint != null) {
            format = switch (formatHint.toLowerCase()) {
                case "pdf" -> FileFormat.PDF;
                case "docx", "doc", "word" -> FileFormat.DOCX;
                case "md", "markdown" -> FileFormat.MARKDOWN;
                default -> detectFormat(userText);
            };
        } else {
            format = detectFormat(userText);
        }
        log.info("检测到文件格式 | format={}", format);

        // 2. 获取对话历史并调用 LLM 生成文档内容（turn-aware 读取，ADR 1E）
        List<Message> history = contextStore.getRecentMessages(10);
        String content = llmClient.chatWithSystemPrompt(CONTENT_GENERATION_PROMPT, userText, history);
        if (content == null || content.trim().isEmpty()) {
            log.warn("LLM 生成文档内容为空");
            return null;
        }
        log.info("LLM 文档内容生成成功 | length={}", content.length());

        // 3. 生成文件名
        String title = extractTitle(content);
        String fileName = title + "." + format.extension;
        String description = switch (format) {
            case PDF -> "基于对话内容生成的PDF文档";
            case DOCX -> "基于对话内容生成的Word文档";
            case MARKDOWN -> "基于对话内容生成的Markdown文档";
        };

        // 4. 渲染文件
        try {
            byte[] fileBytes = switch (format) {
                case PDF -> generatePdf(content, title);
                case DOCX -> generateDocx(content, title);
                case MARKDOWN -> content.getBytes(StandardCharsets.UTF_8);
            };

            if (fileBytes == null || fileBytes.length == 0) {
                log.warn("文件渲染结果为空 | format={}", format);
                return null;
            }

            log.info("文件生成成功 | fileName={} | size={} bytes", fileName, fileBytes.length);
            return new FileGenerationResult(fileBytes, fileName, description);

        } catch (Exception e) {
            log.error("文件渲染异常 | format={} | error={}", format, e.getMessage());
            return null;
        }
    }

    // ==================== 格式检测 ====================

    private enum FileFormat {
        PDF("pdf"),
        DOCX("docx"),
        MARKDOWN("md");

        final String extension;
        FileFormat(String extension) {
            this.extension = extension;
        }
    }

    private FileFormat detectFormat(String userText) {
        String lower = userText.toLowerCase();
        if (lower.contains("markdown") || lower.contains(".md") || lower.contains("md文件")) {
            return FileFormat.MARKDOWN;
        }
        // "pdf" 优先级最高（避免 "PDF文档" 被误判为 DOCX）
        if (lower.contains("pdf")) {
            return FileFormat.PDF;
        }
        if (lower.contains("word") || lower.contains("docx") || lower.contains("文档")) {
            return FileFormat.DOCX;
        }
        // 默认 PDF
        return FileFormat.PDF;
    }

    // ==================== 文件名提取 ====================

    /**
     * 从 LLM 输出的首行标题提取文件名
     */
    private String extractTitle(String content) {
        String firstLine = content.trim().split("\n")[0].trim();
        // 去掉 # 前缀和空格
        String title = firstLine.replaceAll("^#+\\s*", "");
        if (title.length() > 50) {
            title = title.substring(0, 50);
        }
        return title.isEmpty() ? "文档" : title;
    }

    // ==================== PDF 生成（PDFBox 2.0.31） ====================

    private byte[] generatePdf(String content, String title) throws IOException {
        // 管理 TTC 字体生命周期：TTC 必须在 document.save() 之后关闭，
        // 因为 PDType0Font.load(document, ttf, true) 在保存时才会读取字体数据生成子集
        List<Closeable> fontResources = new ArrayList<>();
        try (PDDocument document = new PDDocument()) {
            PDFont font = loadFont(document, fontResources);

            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            PDPageContentStream cs = new PDPageContentStream(document, page);

            float y = PAGE_HEIGHT - MARGIN;
            String[] lines = content.split("\n");

            try {
                for (String line : lines) {
                    // 检查是否需要翻页
                    if (y < MARGIN + LEADING) {
                        cs.close();
                        page = new PDPage(PDRectangle.A4);
                        document.addPage(page);
                        cs = new PDPageContentStream(document, page);
                        y = PAGE_HEIGHT - MARGIN;
                    }

                    // 渲染前清理不支持的字形 + 行内 Markdown 标记
                    String sanitized = stripInlineMarkdown(sanitizePdfText(line));

                    if (sanitized.startsWith("# ")) {
                        // 一级标题：大号加粗居中
                        y = renderTitle(document, cs, font, sanitized.substring(2), 22, y, true);
                    } else if (sanitized.startsWith("## ")) {
                        // 二级标题：中等加粗
                        y = renderTitle(document, cs, font, sanitized.substring(3), 18, y, true);
                    } else if (sanitized.startsWith("### ")) {
                        // 三级标题：正常加粗
                        y = renderTitle(document, cs, font, sanitized.substring(4), 15, y, true);
                    } else if (sanitized.startsWith("- ")) {
                        // 无序列表（使用 - 而非 •，兼容所有 CJK 字体）
                        y = renderText(document, cs, font, "  - " + sanitized.substring(2), 12, y, false);
                    } else if (sanitized.matches("^\\d+\\.\\s.*")) {
                        // 有序列表
                        y = renderText(document, cs, font, "  " + sanitized, 12, y, false);
                    } else if (sanitized.trim().isEmpty()) {
                        // 空行
                        y -= LEADING * 0.5f;
                    } else {
                        // 普通段落
                        y = renderText(document, cs, font, sanitized, 12, y, false);
                    }
                }
            } finally {
                cs.close();
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            document.save(baos);
            return baos.toByteArray();
        } finally {
            // 关闭 TTC 资源（必须在 document.save() 之后）
            for (Closeable resource : fontResources) {
                try {
                    resource.close();
                } catch (Exception e) {
                    log.warn("关闭字体资源异常: {}", e.getMessage());
                }
            }
        }
    }

    private float renderTitle(PDDocument document, PDPageContentStream cs, PDFont font,
                               String text, float fontSize, float y, boolean bold) throws IOException {
        // 标题下移一行间距
        y -= LEADING;
        if (y < MARGIN + LEADING) {
            cs.close();
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            cs = new PDPageContentStream(document, page);
            y = PAGE_HEIGHT - MARGIN - LEADING;
        }

        float textWidth = font.getStringWidth(text) / 1000 * fontSize;
        float x = (PAGE_WIDTH - textWidth) / 2; // 居中

        cs.beginText();
        cs.setFont(font, fontSize);
        cs.newLineAtOffset(Math.max(x, MARGIN), y);
        cs.showText(text);
        cs.endText();

        y -= LEADING * 0.3f; // 标题后小间距
        return y;
    }

    private float renderText(PDDocument document, PDPageContentStream cs, PDFont font,
                              String text, float fontSize, float y, boolean bold) throws IOException {
        y -= LEADING;

        // 自动换行：如果文本超出页面宽度，在空格处截断
        String wrappedText = text;
        float textWidth = font.getStringWidth(wrappedText) / 1000 * fontSize;

        if (textWidth > PAGE_CONTENT_WIDTH) {
            // 简单的截断处理
            int cutIndex = findCutIndex(font, wrappedText, fontSize);
            if (cutIndex > 0) {
                wrappedText = wrappedText.substring(0, cutIndex);
            }
        }

        cs.beginText();
        cs.setFont(font, fontSize);
        cs.newLineAtOffset(MARGIN, y);
        cs.showText(wrappedText);
        cs.endText();

        return y;
    }

    /**
     * 找到在页面宽度内能显示的最大字符位置
     */
    private int findCutIndex(PDFont font, String text, float fontSize) throws IOException {
        for (int i = text.length(); i > 0; i--) {
            String sub = text.substring(0, i);
            float w = font.getStringWidth(sub) / 1000 * fontSize;
            if (w <= PAGE_CONTENT_WIDTH) {
                return i;
            }
        }
        return 1;
    }

    /**
     * 加载中文字体
     * <p>
     * 优先级：系统 TTC 字体（微软雅黑/宋体）→ 系统 TTF 字体（黑体）→ 内置字体
     * <p>
     * TTC (TrueType Collection) 需通过 TrueTypeCollection API 加载，
     * 加载后 TTC 实例需保持打开直到 PDF 文档保存完成，因此由 caller 通过 resources 管理生命周期。
     *
     * @param document  PDF 文档
     * @param resources 用于管理 TTC 等资源的列表（关闭顺序在 document.save() 之后）
     */
    private PDFont loadFont(PDDocument document, List<Closeable> resources) {
        // 1. 尝试加载 TTC 字体（微软雅黑、宋体）
        String[][] ttcFonts = {
                {"C:/Windows/Fonts/msyh.ttc", "MicrosoftYaHei"},
                {"C:/Windows/Fonts/simsun.ttc", "SimSun"},
        };
        for (String[] entry : ttcFonts) {
            String path = entry[0];
            String fontName = entry[1];
            java.io.File file = new java.io.File(path);
            if (!file.exists()) continue;
            try {
                TrueTypeCollection ttc = new TrueTypeCollection(file);
                resources.add(ttc);
                TrueTypeFont ttf = ttc.getFontByName(fontName);
                if (ttf != null) {
                    log.info("加载系统字体: {} -> {}", path, fontName);
                    return PDType0Font.load(document, ttf, true);
                }
            } catch (Exception e) {
                log.warn("加载TTC字体失败: {} | error={}", path, e.getMessage());
            }
        }

        // 2. 尝试加载单文件 TTF 字体（黑体）
        String[] ttfPaths = {
                "C:/Windows/Fonts/simhei.ttf",
        };
        for (String path : ttfPaths) {
            java.io.File file = new java.io.File(path);
            if (!file.exists()) continue;
            try {
                log.info("加载系统字体: {}", path);
                return PDType0Font.load(document, file);
            } catch (Exception e) {
                log.warn("加载系统字体失败: {} | error={}", path, e.getMessage());
            }
        }

        // 3. 降级：内置字体（中文显示为方块）
        log.warn("未找到中文字体，使用内置字体（中文将显示为方块）");
        return PDType1Font.HELVETICA;
    }

    /**
     * 清理 PDF 文本中的无效字形字符
     * <p>
     * CJK 字体（如微软雅黑）缺少 Emoji 和部分符号的 Glyph，
     * 直接调用 {@code showText()} 会抛出 {@code IllegalArgumentException: No glyph for U+XXXX}。
     * 此方法在渲染前移除这些不支持的字符。
     */
    private String sanitizePdfText(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0, len = text.length(); i < len; i++) {
            char c = text.charAt(i);
            int cp = c;
            boolean isSupplementary = false;
            if (Character.isHighSurrogate(c) && i + 1 < len) {
                char low = text.charAt(i + 1);
                if (Character.isLowSurrogate(low)) {
                    cp = Character.toCodePoint(c, low);
                    isSupplementary = true;
                }
            }
            if (isCjkSafeCodepoint(cp)) {
                sb.append(c);
                if (isSupplementary) {
                    sb.append(text.charAt(i + 1));
                    i++;
                }
            }
        }
        return sb.toString().replace('•', '-');
    }

    /**
     * 判断码点是否在 CJK 字体（微软雅黑/宋体）的字形覆盖范围内。
     * 白名单策略：只保留 ASCII + CJK 统一表意文字及扩展区，其余一律丢弃。
     * 这样就不需要逐个符号维护黑名单——每遇到一个新符号缺字就再加一轮补丁。
     */
    private static boolean isCjkSafeCodepoint(int cp) {
        if (cp >= 0x0020 && cp <= 0x007E) return true;  // ASCII 可打印
        if (cp >= 0x00A0 && cp <= 0x00FF) return true;  // Latin-1 补充
        if (cp >= 0x2010 && cp <= 0x2027) return true;  // 通用标点
        if (cp == 0x2026 || cp == 0x2032 || cp == 0x2033) return true;  // … ′ ″
        if (cp >= 0x2E80 && cp <= 0x2FDF) return true;  // CJK 部首补充
        if (cp >= 0x3000 && cp <= 0x303F) return true;  // CJK 符号及标点
        if (cp >= 0x31C0 && cp <= 0x31EF) return true;  // CJK 笔画
        if (cp >= 0x3200 && cp <= 0x33FF) return true;  // 带圈/括号 CJK
        if (cp >= 0x3400 && cp <= 0x4DBF) return true;  // CJK 扩展 A
        if (cp >= 0x4E00 && cp <= 0x9FFF) return true;  // CJK 统一表意文字（基本）
        if (cp >= 0xF900 && cp <= 0xFAFF) return true;  // CJK 兼容表意文字
        if (cp >= 0xFF00 && cp <= 0xFFEF) return true;  // 半角/全角形式
        return false;
    }

    // ==================== DOCX 生成（POI 5.2.5） ====================

    private byte[] generateDocx(String content, String title) throws IOException {
        try (XWPFDocument document = new XWPFDocument()) {
            String[] lines = content.split("\n");

            for (String line : lines) {
                if (line.startsWith("# ")) {
                    createDocxFormattedParagraph(document, line.substring(2), true, 22);
                } else if (line.startsWith("## ")) {
                    createDocxFormattedParagraph(document, line.substring(3), true, 18);
                } else if (line.startsWith("### ")) {
                    createDocxFormattedParagraph(document, line.substring(4), true, 15);
                } else if (line.startsWith("- ")) {
                    createDocxFormattedParagraph(document, "• " + line.substring(2), false, 12);
                } else if (line.matches("^\\d+\\.\\s.*")) {
                    createDocxFormattedParagraph(document, line, false, 12);
                } else if (line.trim().isEmpty()) {
                    // 空行：空段落
                    document.createParagraph();
                } else {
                    createDocxFormattedParagraph(document, line, false, 12);
                }
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            document.write(baos);
            return baos.toByteArray();
        }
    }

    private void createDocxFormattedParagraph(XWPFDocument document, String text, boolean paragraphBold, int fontSize) {
        XWPFParagraph paragraph = document.createParagraph();
        // 标题段落设置间距
        if (paragraphBold && fontSize > 14) {
            paragraph.setSpacingAfter(200);
        }

        // 解析行内 Markdown 格式，分段创建 Runs
        List<TextSegment> segments = parseInlineFormatting(text);
        for (TextSegment segment : segments) {
            if (segment.text().isEmpty()) continue;
            XWPFRun run = paragraph.createRun();
            run.setText(segment.text());
            run.setBold(paragraphBold || segment.bold());
            run.setItalic(segment.italic());
            run.setFontSize(fontSize);
            run.setFontFamily("微软雅黑");
        }
    }

    // ==================== 行内 Markdown 格式解析 ====================

    /**
     * 行内格式化的文本段
     *
     * @param text   文本内容（不含 Markdown 标记）
     * @param bold   是否粗体
     * @param italic 是否斜体
     */
    record TextSegment(String text, boolean bold, boolean italic) {}

    /**
     * 解析行内 Markdown 格式标记
     * <p>支持：{@code **粗体**}、{@code *斜体*}
     * 先解析粗体，剩余部分再解析斜体，避免 {@code **} 与 {@code *} 冲突。</p>
     */
    private List<TextSegment> parseInlineFormatting(String line) {
        if (line == null || line.isEmpty()) {
            return List.of(new TextSegment(line != null ? line : "", false, false));
        }
        List<TextSegment> result = new ArrayList<>();

        // 第一遍：提取 **bold**
        java.util.regex.Pattern boldPattern = java.util.regex.Pattern.compile("\\*\\*(.+?)\\*\\*");
        java.util.regex.Matcher boldMatcher = boldPattern.matcher(line);
        int lastEnd = 0;

        while (boldMatcher.find()) {
            // 粗体前的文本 → 可能包含 *italic*
            String before = line.substring(lastEnd, boldMatcher.start());
            if (!before.isEmpty()) {
                result.addAll(parseItalic(before));
            }
            // 粗体段
            result.add(new TextSegment(boldMatcher.group(1), true, false));
            lastEnd = boldMatcher.end();
        }

        // 剩余文本
        String remaining = line.substring(lastEnd);
        if (!remaining.isEmpty()) {
            result.addAll(parseItalic(remaining));
        }

        // 完全没有匹配到任何标记
        if (result.isEmpty()) {
            result.add(new TextSegment(line, false, false));
        }

        return result;
    }

    /**
     * 从文本中提取 *italic* 片段
     */
    private List<TextSegment> parseItalic(String text) {
        List<TextSegment> segments = new ArrayList<>();
        java.util.regex.Pattern italicPattern = java.util.regex.Pattern.compile("\\*(.+?)\\*");
        java.util.regex.Matcher matcher = italicPattern.matcher(text);
        int lastEnd = 0;

        while (matcher.find()) {
            String before = text.substring(lastEnd, matcher.start());
            if (!before.isEmpty()) {
                segments.add(new TextSegment(before, false, false));
            }
            segments.add(new TextSegment(matcher.group(1), false, true));
            lastEnd = matcher.end();
        }

        String remaining = text.substring(lastEnd);
        if (!remaining.isEmpty()) {
            segments.add(new TextSegment(remaining, false, false));
        }

        return segments;
    }

    /**
     * 去除行内 Markdown 标记（用于 PDF 等不支持富文本的场景）
     */
    private String stripInlineMarkdown(String text) {
        if (text == null || text.isEmpty()) return text;
        return text.replaceAll("\\*\\*(.+?)\\*\\*", "$1")
                   .replaceAll("\\*(.+?)\\*", "$1");
    }

    // ==================== 结果类型 ====================

    /**
     * 文件生成结果
     *
     * @param fileBytes   文件字节数据
     * @param fileName    文件名（含扩展名）
     * @param description 文件描述
     */
    public record FileGenerationResult(byte[] fileBytes, String fileName, String description) {}
}
