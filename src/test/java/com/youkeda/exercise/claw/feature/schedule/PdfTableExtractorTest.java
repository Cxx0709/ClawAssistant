package com.youkeda.exercise.claw.feature.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.exercise.claw.feature.schedule.pdf.PdfTableExtractor;
import com.youkeda.exercise.claw.feature.schedule.pdf.ScheduleCell;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试 PdfTableExtractor 对真实课表 PDF 的解析效果
 */
class PdfTableExtractorTest {

    private PdfTableExtractor extractor;
    private byte[] pdfBytes;

    @BeforeEach
    void setUp() throws Exception {
        extractor = new PdfTableExtractor();
        File dir = new File("C:\\Users\\18395\\Downloads\\");
        File[] pdfs = dir.listFiles((d, n) -> n.endsWith(".pdf") && n.contains("课表"));
        if (pdfs != null && pdfs.length > 0) {
            pdfBytes = Files.readAllBytes(pdfs[0].toPath());
        }
    }

    @Test
    @DisplayName("PDF 表格恢复：概率统计(周一3-4节 双周)")
    void testProbabilityStatistics() {
        if (pdfBytes == null) { System.out.println("SKIP: PDF not found"); return; }
        List<ScheduleCell> cells = extractor.extract(pdfBytes);
        Map<String, ScheduleCell> byKey = cells.stream()
                .collect(Collectors.toMap(c -> c.getDayOfWeek() + ":" + c.getPeriod(), c -> c));

        // 概率统计 周一3-4节(双)
        ScheduleCell cell = byKey.get("1:3-4");
        assertNotNull(cell, "应恢复出周一3-4节单元格");
        assertTrue(cell.getContent().contains("概率统计"), "周一3-4节应包含概率统计");
    }

    @Test
    @DisplayName("PDF 表格恢复：离散数学(周二3-4节)")
    void testDiscreteMath() {
        if (pdfBytes == null) { System.out.println("SKIP: PDF not found"); return; }
        List<ScheduleCell> cells = extractor.extract(pdfBytes);
        Map<String, ScheduleCell> byKey = cells.stream()
                .collect(Collectors.toMap(c -> c.getDayOfWeek() + ":" + c.getPeriod(), c -> c));

        ScheduleCell cell = byKey.get("2:3-4");
        assertNotNull(cell, "应恢复出周二3-4节单元格");
        String content = cell.getContent();
        System.out.println("  周二3-4节原始内容: " + content);
        assertTrue(content.contains("离散数学") || content.contains("离散数学"),
                "周二3-4节应包含离散数学，实际内容: " + content);
    }

    @Test
    @DisplayName("PDF 表格恢复：大学物理Ⅱ(周三3-4节 单周)")
    void testUniversityPhysics() {
        if (pdfBytes == null) { System.out.println("SKIP: PDF not found"); return; }
        List<ScheduleCell> cells = extractor.extract(pdfBytes);
        Map<String, ScheduleCell> byKey = cells.stream()
                .collect(Collectors.toMap(c -> c.getDayOfWeek() + ":" + c.getPeriod(), c -> c));

        ScheduleCell cell = byKey.get("3:3-4");
        assertNotNull(cell, "应恢复出周三3-4节单元格");
        assertTrue(cell.getContent().contains("大学物理"), "周三3-4节应包含大学物理Ⅱ");
        assertTrue(cell.getContent().contains("(单)") || cell.getContent().contains("单周"),
                "大学物理应包含(单)或单周标记");
    }

    @Test
    @DisplayName("PDF 表格恢复：Java程序设计(周四3-4节)")
    void testJavaProgramming() {
        if (pdfBytes == null) { System.out.println("SKIP: PDF not found"); return; }
        List<ScheduleCell> cells = extractor.extract(pdfBytes);
        Map<String, ScheduleCell> byKey = cells.stream()
                .collect(Collectors.toMap(c -> c.getDayOfWeek() + ":" + c.getPeriod(), c -> c));

        ScheduleCell cell = byKey.get("4:3-4");
        assertNotNull(cell, "应恢复出周四3-4节单元格");
        assertTrue(cell.getContent().contains("Java"), "周四3-4节应包含Java程序设计");
    }

    @Test
    @DisplayName("PDF 表格恢复：通用英语(周五3-4节)")
    void testEnglish() {
        if (pdfBytes == null) { System.out.println("SKIP: PDF not found"); return; }
        List<ScheduleCell> cells = extractor.extract(pdfBytes);
        Map<String, ScheduleCell> byKey = cells.stream()
                .collect(Collectors.toMap(c -> c.getDayOfWeek() + ":" + c.getPeriod(), c -> c));

        ScheduleCell cell = byKey.get("5:3-4");
        assertNotNull(cell, "应恢复出周五3-4节单元格");
        assertTrue(cell.getContent().contains("通用英语"), "周五3-4节应包含通用英语");
    }

    @Test
    @DisplayName("PDF 表格恢复：3-4节行所有5门课的星期全部正确")
    void testAllFiveColumnsCorrect() {
        if (pdfBytes == null) { System.out.println("SKIP: PDF not found"); return; }
        List<ScheduleCell> cells = extractor.extract(pdfBytes);
        Map<String, ScheduleCell> byKey = cells.stream()
                .collect(Collectors.toMap(c -> c.getDayOfWeek() + ":" + c.getPeriod(), c -> c));

        // 验证3-4节行所有5列
        assertTrue(byKey.containsKey("1:3-4"), "周一3-4节");
        assertTrue(byKey.containsKey("2:3-4"), "周二3-4节");
        assertTrue(byKey.containsKey("3:3-4"), "周三3-4节");
        assertTrue(byKey.containsKey("4:3-4"), "周四3-4节");
        assertTrue(byKey.containsKey("5:3-4"), "周五3-4节");

        assertTrue(byKey.get("1:3-4").getContent().contains("概率统计"), "周一=概率统计");
        assertTrue(byKey.get("2:3-4").getContent().contains("离散数学"), "周二=离散数学");
        assertTrue(byKey.get("3:3-4").getContent().contains("大学物理"), "周三=大学物理Ⅱ(2)");
        assertTrue(byKey.get("4:3-4").getContent().contains("Java"), "周四=Java程序设计");
        assertTrue(byKey.get("5:3-4").getContent().contains("通用英语"), "周五=通用英语");
    }

    @Test
    @DisplayName("输出所有恢复的单元格用于人工验证")
    void printAllCells() {
        if (pdfBytes == null) { System.out.println("SKIP: PDF not found"); return; }
        List<ScheduleCell> cells = extractor.extract(pdfBytes);
        System.out.println("\n===== PdfTableExtractor 恢复结果 =====");
        System.out.println("共 " + cells.size() + " 个单元格:\n");
        for (ScheduleCell cell : cells) {
            String dayName = getDayName(cell.getDayOfWeek());
            String content = cell.getContent();
            System.out.println("  day=" + cell.getDayOfWeek() + "(" + dayName + ") "
                    + cell.getPeriod() + "节 | " + content);
        }
    }

    @Test
    @DisplayName("验证每个单元格包含正确的课程名称")
    void verifyCourseNames() {
        if (pdfBytes == null) { System.out.println("SKIP: PDF not found"); return; }
        List<ScheduleCell> cells = extractor.extract(pdfBytes);
        Map<String, String> expected = new java.util.HashMap<>();
        expected.put("1:3-4", "概率统计");
        expected.put("1:5-6", "计算机组成原理");
        expected.put("1:7-8", "离散数学");
        expected.put("2:3-4", "离散数学");
        expected.put("2:5-6", "大学物理");
        expected.put("2:7-8", "计算机组成原理");
        expected.put("3:1-2", "概率统计");
        expected.put("3:3-4", "大学物理");
        expected.put("4:3-4", "Java");
        expected.put("5:5-6", "数据库原理");
        expected.put("5:3-4", "通用英语");
        expected.put("5:7-8", "专业导论");

        for (var entry : expected.entrySet()) {
            String key = entry.getKey();
            String expectedName = entry.getValue();

            ScheduleCell cell = cells.stream()
                .filter(c -> (c.getDayOfWeek() + ":" + c.getPeriod()).equals(key))
                .findFirst().orElse(null);

            assertNotNull(cell, "缺少 key=" + key);
            String content = cell.getContent();
            String[] dow = {"", "一", "二", "三", "四", "五", "六", "日"};
            int d = cell.getDayOfWeek();
            System.out.println("  [" + key + "] 内容开头=" + content.substring(0, Math.min(30, content.length()))
                    + " 期望包含=" + expectedName);
            assertTrue(content.contains(expectedName),
                    "dow=" + d + "(" + (d >= 1 && d <= 7 ? dow[d] : "?") + ") "
                    + cell.getPeriod() + "节 应包含【" + expectedName + "】，实际内容开头: "
                    + content.substring(0, Math.min(50, content.length())));
        }
    }

    private String getDayName(int d) {
        String[] names = {"", "一", "二", "三", "四", "五", "六", "日"};
        return d >= 1 && d <= 7 ? names[d] : "?";
    }
}