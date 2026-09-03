package com.youkeda.exercise.claw.feature.schedule.pdf;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * PDF 课表表格恢复模块
 *
 * <p>使用 Apache PDFBox 直接解析 PDF 中每个文字块的位置坐标，
 * 根据 x 坐标判断星期列，根据内容中的 "(X-X节)" 标记判断节次，
 * 恢复二维课表结构。
 *
 * <p>核心策略：
 * <ol>
 *   <li>提取所有文字块 (text, x, y)</li>
 *   <li>识别表头 "星期一~星期日" → 确定列边界</li>
 *   <li>根据 x 坐标将文字块分配到对应星期列</li>
 *   <li>根据内容中的 "(X-X节)" 模式提取节次</li>
 *   <li>按 (星期, 节次) 分组聚合为 {@link ScheduleCell}</li>
 * </ol>
 */
@Component
public class PdfTableExtractor {

    private static final Logger log = LoggerFactory.getLogger(PdfTableExtractor.class);

    /** 星期标记：用于识别表头和判断列 */
    private static final String[] HEADER_NAMES = {"星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"};

    /** 节次匹配模式：如 "(3-4节)" */
    private static final Pattern PERIOD_PATTERN = Pattern.compile("\\((\\d+)-(\\d+)节\\)");

    /** 课程名称标记 */
    private static final String COURSE_MARKER = "▲";

    /** 图例/页脚行起始特征 */
    private static final Pattern LEGEND_PATTERN = Pattern.compile("^\\s*[:：]");

    /**
     * 从 PDF 字节中提取课表单元格列表
     *
     * @param pdfBytes PDF 文件字节
     * @return 恢复后的课表单元格列表
     */
    public List<ScheduleCell> extract(byte[] pdfBytes) {
        List<TextBlock> allBlocks = extractTextBlocks(pdfBytes);
        if (allBlocks.isEmpty()) {
            log.warn("PDF 未提取到任何文字块");
            return List.of();
        }

        // 1. 查找表头列位置
        Map<Integer, Float> headerXPositions = findHeaderPositions(allBlocks);
        if (headerXPositions.isEmpty()) {
            log.warn("PDF 中未找到星期表头，无法恢复表格结构");
            return List.of();
        }
        log.debug("检测到表头列位置: {}", headerXPositions);

        // 2. 构建列边界
        float[] boundaries = buildColumnBoundaries(headerXPositions);

        // 3. 找到第一个课程起始点的 y 坐标（标记为 ▲ 的最低 y 值），用于排除页眉/浮动标注
        float firstCourseY = findFirstCourseY(allBlocks);
        log.debug("检测到第一个课程 y={}", firstCourseY);

        // 4. 过滤非课表内容（表头、页眉页脚、左边标签等）
        List<TextBlock> courseBlocks = filterCourseContent(allBlocks, boundaries, firstCourseY);

        // 4. 按列分组，每列内按 y 排序
        Map<Integer, List<TextBlock>> blocksByColumn = groupByColumn(courseBlocks, boundaries);

        // 5. 每列内按节次分组 → 生成 ScheduleCell
        List<ScheduleCell> cells = new ArrayList<>();
        for (Map.Entry<Integer, List<TextBlock>> entry : blocksByColumn.entrySet()) {
            int dayOfWeek = entry.getKey();
            List<TextBlock> colBlocks = entry.getValue();
            // 按 y 排序
            colBlocks.sort(Comparator.comparingDouble(b -> b.y));
            cells.addAll(groupBlocksByPeriod(dayOfWeek, colBlocks));
        }

        // 6. 合并同 key 的单元格（处理多行节次信息分散）
        List<ScheduleCell> merged = mergeCellsByKey(cells);

        // 7. 去除内容为空或纯图例的单元格
        List<ScheduleCell> result = merged.stream()
                .filter(c -> c.getContent() != null && !c.getContent().isBlank())
                .filter(c -> !isLegendContent(c.getContent()))
                .collect(Collectors.toList());

        log.info("PDF 课表表格恢复完成 | 原始={} 合并后={} 最终={}", cells.size(), merged.size(), result.size());
        return result;
    }

    // ==================== 文字块提取 ====================

    /**
     * 使用 PDFBox 提取 PDF 中每个文字块的位置和内容
     */
    private List<TextBlock> extractTextBlocks(byte[] pdfBytes) {
        List<TextBlock> blocks = new ArrayList<>();
        try (PDDocument doc = PDDocument.load(new ByteArrayInputStream(pdfBytes))) {
            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<TextPosition> textPositions) throws IOException {
                    if (text == null || text.trim().isEmpty()) return;
                    float x = textPositions.get(0).getX();
                    float y = textPositions.get(0).getY();
                    blocks.add(new TextBlock(text.trim(), x, y));
                }
            };
            stripper.setSortByPosition(true);
            stripper.getText(doc);
        } catch (IOException e) {
            log.error("PDF 文字块提取失败", e);
        }
        return blocks;
    }

    // ==================== 表头检测 ====================

    /**
     * 在文字块中查找 "星期一"~"星期日" 表头，返回 {dayOfWeek → x坐标} 映射
     */
    private Map<Integer, Float> findHeaderPositions(List<TextBlock> blocks) {
        Map<Integer, Float> result = new HashMap<>();
        for (TextBlock block : blocks) {
            for (int d = 0; d < 7; d++) {
                if (block.text.contains(HEADER_NAMES[d])) {
                    result.put(d + 1, block.x);
                    break;
                }
            }
        }
        return result;
    }

    /**
     * 根据表头 x 坐标构建列边界数组
     * boundaries[d] = 第 d 列的左边界（d 从 1 开始）
     * boundaries[8] = 最后一列的右边界
     */
    private float[] buildColumnBoundaries(Map<Integer, Float> headerX) {
        float[] boundaries = new float[9]; // index 1-7 每列左边界，index 8 为右边界
        for (int d = 1; d <= 7; d++) {
            boundaries[d] = headerX.getOrDefault(d, 0f);
        }

        // 计算列宽度（取中位数列距）
        List<Float> gaps = new ArrayList<>();
        for (int d = 2; d <= 7; d++) {
            if (boundaries[d] > 0 && boundaries[d - 1] > 0) {
                gaps.add(boundaries[d] - boundaries[d - 1]);
            }
        }

        // 将表头的 x 位置作为列中心，计算列边界 = 相邻两列中心的中点
        // 首先找到第一个有值的列，作为基准
        int firstCol = 1;
        while (firstCol <= 7 && boundaries[firstCol] == 0) firstCol++;
        int lastCol = 7;
        while (lastCol >= 1 && boundaries[lastCol] == 0) lastCol--;

        if (firstCol > lastCol) return boundaries;

        // 将每列的 header x 视为列中心，计算列边界
        for (int d = firstCol + 1; d <= lastCol; d++) {
            float mid = (boundaries[d - 1] + boundaries[d]) / 2f;
            // boundaries[d] 原本是第 d 列的 center，改为第 d 列的 leftBoundary
        }
        // 重构：用 midpoint 作为列分界线
        float[] newBoundaries = new float[9];
        newBoundaries[firstCol] = 0; // 第一列左边界为 0
        for (int d = firstCol + 1; d <= lastCol; d++) {
            newBoundaries[d] = (headerX.get(d - 1) + headerX.get(d)) / 2f;
        }
        newBoundaries[lastCol + 1] = Float.MAX_VALUE; // 最后一列右边界为无穷

        // 对左边无数据的列，用第一个列中心 - 列宽度 推算
        float avgGap = gaps.isEmpty() ? 100f : (float) gaps.stream().mapToDouble(Float::doubleValue).average().orElse(100.0);
        for (int d = firstCol - 1; d >= 1; d--) {
            newBoundaries[d] = newBoundaries[d + 1] - (float) avgGap;
        }

        return newBoundaries;
    }

    // ==================== 内容过滤 ====================

    /**
     * 找到第一个含 ▲ 的文字块的 y 坐标（课程内容起始线）
     */
    private float findFirstCourseY(List<TextBlock> blocks) {
        for (TextBlock block : blocks) {
            if (block.text.contains(COURSE_MARKER)) {
                return block.y;
            }
        }
        return 0;
    }

    /**
     * 过滤出课程内容文字块（排除表头、页眉页脚、左侧标签等）
     */
    private List<TextBlock> filterCourseContent(List<TextBlock> blocks, float[] boundaries, float firstCourseY) {
        float headerY = findHeaderY(blocks);

        return blocks.stream()
                .filter(b -> isCourseContent(b, headerY, firstCourseY, boundaries))
                .collect(Collectors.toList());
    }

    /**
     * 找到表头行的 y 坐标
     */
    private float findHeaderY(List<TextBlock> blocks) {
        for (TextBlock block : blocks) {
            if (block.text.contains("星期一") || block.text.contains("星期")) {
                return block.y;
            }
        }
        return 0;
    }

    /**
     * 判断文字块是否为课程内容
     */
    private boolean isCourseContent(TextBlock block, float headerY, float firstCourseY, float[] boundaries) {
        String text = block.text;

        // 排除非内容：纯数字的节次标签、页眉页脚
        if (isLabelText(text)) return false;

        // 排除表头行附近的文字（标题、表头等）
        if (headerY > 0 && Math.abs(block.y - headerY) < 10) return false;

        // 排除表格上方浮动内容（页眉、浮动标注等）：必须在第一个课程之前
        if (firstCourseY > 0 && block.y < firstCourseY - 5) return false;

        // 排除页脚标记
        if (text.contains("打印时间") || text.contains("学号") || text.contains("课表")) return false;
        if (text.contains("实习") || text.contains("实践") || text.contains("线上")) {
            // 这些如果是课程内容的一部分则保留（如实践课程、线上课程标记）
            // 但纯图例文字（位于表格外）应排除
            // 判断：如果它在任何列范围内，可能还是课程内容
            int col = getColumn(block.x, boundaries);
            if (col == 0) return false;
        }

        return true;
    }

    /**
     * 判断是否为纯标签文本（左侧节次数字、时间段标记等）
     */
    private boolean isLabelText(String text) {
        String t = text.trim();
        // 纯数字
        if (t.matches("\\d+")) return true;
        // "上午"、"下午"、"晚上"、"时间段"、"节次"
        if (t.equals("上午") || t.equals("下午") || t.equals("晚上")) return true;
        if (t.contains("时间段") || t.contains("节次")) return true;
        return false;
    }

    // ==================== 列映射 ====================

    /**
     * 根据 x 坐标判断属于哪个星期列（1-7），不在任何列返回 0
     */
    private int getColumn(float x, float[] boundaries) {
        for (int d = 1; d <= 7; d++) {
            if (x >= boundaries[d] && x < boundaries[d + 1]) {
                return d;
            }
        }
        return 0;
    }

    /**
     * 按星期列分组
     */
    private Map<Integer, List<TextBlock>> groupByColumn(List<TextBlock> blocks, float[] boundaries) {
        Map<Integer, List<TextBlock>> result = new HashMap<>();
        for (TextBlock block : blocks) {
            int col = getColumn(block.x, boundaries);
            if (col > 0) {
                result.computeIfAbsent(col, k -> new ArrayList<>()).add(block);
            }
        }
        return result;
    }

    // ==================== 节次分组 ====================

    /**
     * 在一列的文本块中，以 ▲ 课程名称为分隔依据分组
     *
     * <p>PDF 中每个课程单元格的结构为：
     *   - 第1个块: 课程名称▲（如"概率统计▲"）
     *   - 第2个块: (X-X节)...（含节次标记）
     *   - 后续块: 详情文字
     *
     * <p>当遇到新的 ▲ 块时，保存上一个课程的单元格。
     */
    private List<ScheduleCell> groupBlocksByPeriod(int dayOfWeek, List<TextBlock> blocks) {
        List<ScheduleCell> cells = new ArrayList<>();

        String currentPeriod = null;
        StringBuilder currentContent = new StringBuilder();
        boolean hasCourseMarker = false;

        for (TextBlock block : blocks) {
            boolean isCourseStart = block.text.contains(COURSE_MARKER);

            if (isCourseStart && hasCourseMarker) {
                // 新的课程开始 → 保存上一个
                if (currentPeriod != null && currentContent.length() > 0) {
                    cells.add(new ScheduleCell(dayOfWeek, currentPeriod, currentContent.toString().trim()));
                }
                currentContent = new StringBuilder();
                currentPeriod = null;
            }

            // 检测节次标记
            String period = extractPeriod(block.text);
            if (period != null) {
                currentPeriod = period;
            }

            if (isCourseStart) hasCourseMarker = true;

            // 追加文本（仅当已遇到课程起始标记后）
            if (hasCourseMarker) {
                if (currentContent.length() > 0) currentContent.append(" ");
                currentContent.append(block.text);
            }
        }

        // 保存最后一个
        if (hasCourseMarker && currentPeriod != null && currentContent.length() > 0) {
            cells.add(new ScheduleCell(dayOfWeek, currentPeriod, currentContent.toString().trim()));
        }

        return cells;
    }

    /**
     * 将一行的文字块拼接成完整文本
     */
    private String joinBlocks(List<TextBlock> blocks) {
        StringBuilder sb = new StringBuilder();
        for (TextBlock b : blocks) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(b.text);
        }
        return sb.toString().trim();
    }

    /**
     * 从文本中提取节次，如 "(3-4节)" → "3-4"
     */
    private String extractPeriod(String text) {
        if (text == null) return null;
        Matcher m = PERIOD_PATTERN.matcher(text);
        if (m.find()) {
            return m.group(1) + "-" + m.group(2);
        }
        return null;
    }

    /**
     * 合并同 (dayOfWeek, period) 的单元格
     * <p>PDF 中同一单元格的文字可能因 y 坐标分散成多个 TextBlock 段，
     * 导致 groupBlocksByPeriod 产生重复的 ScheduleCell。
     * 此方法将同一 (星期, 节次) 的单元格内容追加合并。</p>
     */
    private List<ScheduleCell> mergeCellsByKey(List<ScheduleCell> cells) {
        Map<String, ScheduleCell> merged = new LinkedHashMap<>();
        for (ScheduleCell cell : cells) {
            String key = cell.getDayOfWeek() + ":" + cell.getPeriod();
            ScheduleCell existing = merged.get(key);
            if (existing == null) {
                merged.put(key, new ScheduleCell(cell.getDayOfWeek(), cell.getPeriod(), cell.getContent()));
            } else {
                existing.setContent(existing.getContent() + " " + cell.getContent());
            }
        }
        return new ArrayList<>(merged.values());
    }

    /**
     * 判断是否为图例内容（以 : 或 ：开头的行）
     */
    private boolean isLegendContent(String content) {
        return content != null && LEGEND_PATTERN.matcher(content.trim()).find();
    }

    // ==================== 内部类 ====================

    /**
     * PDF 文字块：包含文本内容和坐标
     */
    static class TextBlock {
        final String text;
        final float x;
        final float y;

        TextBlock(String text, float x, float y) {
            this.text = text;
            this.x = x;
            this.y = y;
        }

        @Override
        public String toString() {
            return String.format("(%.0f,%.0f) %s", x, y, text);
        }
    }
}