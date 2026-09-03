package com.youkeda.exercise.claw.feature.schedule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 学期检测器
 *
 * <p>根据多种来源检测学期信息，按优先级：
 * <ol>
 *   <li>用户消息中明确指定的学年和学期（通过 LLM 参数传入）</li>
 *   <li>文件名中包含的学期信息（如 "2026秋季课表.xlsx"）</li>
 *   <li>文件内容中提取的学期信息（如 "2026-2027学年第一学期"）</li>
 *   <li>系统根据当前日期自动推算</li>
 * </ol>
 */
@Component
public class SemesterDetector {

    private static final Logger log = LoggerFactory.getLogger(SemesterDetector.class);

    /** 匹配文件名中的学年，如 "2026", "2026秋季课表" */
    private static final Pattern YEAR_PATTERN = Pattern.compile("(20\\d{2})");

    /** 匹配文件名中的学期中文 */
    private static final Pattern TERM_CN_PATTERN = Pattern.compile("[春秋]");

    /** 匹配文件名中的学期英文 */
    private static final Pattern TERM_EN_PATTERN = Pattern.compile("(?i)(spring|fall|summer)");

    /** 匹配文件内容中的学年范围，如 "2026-2027学年" */
    private static final Pattern YEAR_RANGE_PATTERN = Pattern.compile("(20\\d{2})\\s*[-–—]\\s*(20\\d{2})\\s*学?年");

    /** 匹配文件内容中的学期数字，如 "第一学期" / "第二学期" / "第1学期" / "第2学期" */
    private static final Pattern TERM_NUM_PATTERN = Pattern.compile("[第]?[一两二三四1-4]\\s*[学]?期");

    /**
     * 从 LLM 参数中检测学期（用户显式指定）
     *
     * @param userId       用户标识
     * @param academicYear 学年（如 2026）
     * @param term         学期（SPRING / FALL）
     * @return 学期实体（未持久化），如果参数无效返回 null
     */
    public SemesterEntity detectFromParams(String userId, Integer academicYear, String term) {
        if (academicYear == null || academicYear < 2000 || academicYear > 2100) {
            return null;
        }
        if (term == null || term.isBlank()) {
            return null;
        }
        if (!SemesterEntity.TERM_SPRING.equals(term) && !SemesterEntity.TERM_FALL.equals(term)) {
            return null;
        }
        LocalDate startDate = calculateStartDate(academicYear, term);
        if (startDate == null) return null;

        log.info("从用户参数检测到学期 | userId={} | year={} | term={} | startDate={}",
                userId, academicYear, term, startDate);
        return new SemesterEntity(userId, academicYear, term, startDate,
                SemesterEntity.SOURCE_USER_CONFIRM);
    }

    /**
     * 从文件名和/或内容预览中检测学期
     *
     * @param userId         用户标识
     * @param fileName       文件名（可为 null）
     * @param contentPreview 文件内容预览（可为 null）
     * @return 学期实体（未持久化），无法确定返回 null
     */
    public SemesterEntity detectFromFile(String userId, String fileName, String contentPreview) {
        Integer year = null;
        String term = null;

        // 1. 尝试从文件名提取
        if (fileName != null && !fileName.isBlank()) {
            year = extractYear(fileName);
            term = extractTerm(fileName);
            log.debug("从文件名提取学期信息 | fileName={} | year={} | term={}", fileName, year, term);
        }

        // 2. 文件名未提取到，尝试从内容提取
        if ((year == null || term == null) && contentPreview != null && !contentPreview.isBlank()) {
            Integer contentYear = extractYearFromContent(contentPreview);
            String contentTerm = extractTermFromContent(contentPreview);
            if (year == null) year = contentYear;
            if (term == null) term = contentTerm;
            log.debug("从内容提取学期信息 | contentPreview={} | year={} | term={}",
                    truncate(contentPreview, 100), year, term);
        }

        if (year != null && term != null) {
            LocalDate startDate = calculateStartDate(year, term);
            if (startDate == null) return null;

            log.info("从文件信息检测到学期 | userId={} | year={} | term={} | startDate={}",
                    userId, year, term, startDate);
            return new SemesterEntity(userId, year, term, startDate,
                    SemesterEntity.SOURCE_AUTO_DETECT);
        }

        log.debug("无法从文件信息确定学期 | userId={} | fileName={}", userId, fileName);
        return null;
    }

    /**
     * 系统自动推算学期（基于当前日期）
     *
     * @param userId 用户标识
     * @return 学期实体（未持久化）
     */
    public SemesterEntity detectAuto(String userId) {
        SemesterEntity semester = SemesterService.detectSemester(userId, LocalDate.now());
        log.info("自动推算学期 | userId={} | display={} | startDate={}",
                userId, semester.getDisplayName(), semester.getStartDate());
        return semester;
    }

    // ==================== 文件名字段提取 ====================

    /**
     * 从文件名提取学年
     */
    Integer extractYear(String text) {
        if (text == null) return null;
        Matcher m = YEAR_PATTERN.matcher(text);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        return null;
    }

    /**
     * 从文件名提取学期
     */
    String extractTerm(String text) {
        if (text == null) return null;

        // 中文匹配
        Matcher cn = TERM_CN_PATTERN.matcher(text);
        if (cn.find()) {
            String ch = cn.group();
            if ("春".equals(ch)) return SemesterEntity.TERM_SPRING;
            if ("秋".equals(ch)) return SemesterEntity.TERM_FALL;
        }

        // 英文匹配
        Matcher en = TERM_EN_PATTERN.matcher(text);
        if (en.find()) {
            String eng = en.group().toLowerCase();
            if ("spring".equals(eng)) return SemesterEntity.TERM_SPRING;
            if ("fall".equals(eng) || "summer".equals(eng)) return SemesterEntity.TERM_FALL;
        }

        return null;
    }

    // ==================== 文本内容字段提取 ====================

    /**
     * 从文本内容提取学年
     */
    Integer extractYearFromContent(String text) {
        if (text == null) return null;

        // 先匹配学年范围 "2026-2027学年" → 取2026（秋季学期）
        Matcher range = YEAR_RANGE_PATTERN.matcher(text);
        if (range.find()) {
            // 第一学期（秋季）取前一年，第二学期（春季）取后一年
            String termNum = extractTermNumber(text);
            if ("2".equals(termNum)) {
                // 第二学期（春季）→ 取后一年
                return Integer.parseInt(range.group(2));
            }
            // 默认第一学期（秋季）→ 取前一年
            return Integer.parseInt(range.group(1));
        }

        // 再匹配单独年份
        Matcher single = YEAR_PATTERN.matcher(text);
        if (single.find()) {
            return Integer.parseInt(single.group(1));
        }
        return null;
    }

    /**
     * 从文本内容提取学期
     */
    String extractTermFromContent(String text) {
        if (text == null) return null;

        // 先匹配中文春秋
        Matcher cn = TERM_CN_PATTERN.matcher(text);
        if (cn.find()) {
            String ch = cn.group();
            if ("春".equals(ch)) return SemesterEntity.TERM_SPRING;
            if ("秋".equals(ch)) return SemesterEntity.TERM_FALL;
        }

        // 匹配学期数字
        String termNum = extractTermNumber(text);
        if ("1".equals(termNum)) return SemesterEntity.TERM_FALL;
        if ("2".equals(termNum)) return SemesterEntity.TERM_SPRING;

        return null;
    }

    /**
     * 提取学期数字：第一学期→1, 第二学期→2
     */
    private String extractTermNumber(String text) {
        if (text == null) return null;
        Matcher m = TERM_NUM_PATTERN.matcher(text);
        if (m.find()) {
            String match = m.group();
            if (match.contains("一") || match.contains("1")) return "1";
            if (match.contains("二") || match.contains("2")) return "2";
        }
        return null;
    }

    // ==================== 工具方法 ====================

    /**
     * 根据学年和学期计算起始日期（第 1 周周一）
     *
     * <p>春季：3月1日所在周一
     * <br>秋季：9月1日所在周一
     */
    public static LocalDate calculateStartDate(int academicYear, String term) {
        if (SemesterEntity.TERM_FALL.equals(term)) {
            return SemesterService.getMondayOfWeek(LocalDate.of(academicYear, 9, 1));
        } else if (SemesterEntity.TERM_SPRING.equals(term)) {
            return SemesterService.getMondayOfWeek(LocalDate.of(academicYear, 3, 1));
        }
        return null;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}