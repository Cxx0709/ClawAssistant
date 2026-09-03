package com.youkeda.exercise.claw.feature.schedule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Optional;

/**
 * 学期业务服务
 *
 * <p>提供学期创建、查询、周次计算等核心业务逻辑。
 * 持久化操作委托 {@link SemesterRepository}，以 {@code userId} 作为隔离键。
 *
 * <p>学期决定了课程表第一周的日期，所有周次计算均基于 {@link SemesterEntity#getCurrentWeek()}。
 */
@Service
public class SemesterService {

    private static final Logger log = LoggerFactory.getLogger(SemesterService.class);

    private final SemesterRepository semesterRepository;

    public SemesterService(SemesterRepository semesterRepository) {
        this.semesterRepository = semesterRepository;
    }

    // ==================== 查询 ====================

    /**
     * 获取用户的当前有效学期
     *
     * <p>返回最近一条学期记录。如果没有则返回 {@link Optional#empty()}。
     *
     * @param userId 用户标识
     * @return 当前学期
     */
    public Optional<SemesterEntity> getCurrentSemester(String userId) {
        return semesterRepository.findLatestByUserId(userId);
    }

    /**
     * 计算用户当前教学周
     *
     * <p>如果用户有学期记录，基于该学期的 startDate 计算当前周；
     * 否则返回 -1 表示学期未确定。
     *
     * @param userId 用户标识
     * @return 当前教学周，学期未开始或无学期返回 -1
     */
    public int getCurrentWeek(String userId) {
        Optional<SemesterEntity> semesterOpt = getCurrentSemester(userId);
        if (semesterOpt.isEmpty()) {
            log.debug("用户无学期记录，返回 -1 | userId={}", userId);
            return -1;
        }
        return semesterOpt.get().getCurrentWeek();
    }

    /**
     * 判断当前周是否为单周
     *
     * @param userId 用户标识
     * @return 当前为单周返回 true，学期未开始或无学期返回 false
     */
    public boolean isOddWeek(String userId) {
        int week = getCurrentWeek(userId);
        return week > 0 && week % 2 == 1;
    }

    /**
     * 判断当前周是否为双周
     *
     * @param userId 用户标识
     * @return 当前为双周返回 true，学期未开始或无学期返回 false
     */
    public boolean isEvenWeek(String userId) {
        int week = getCurrentWeek(userId);
        return week > 0 && week % 2 == 0;
    }

    /**
     * 判断用户是否已有学期记录
     *
     * @param userId 用户标识
     * @return 有学期记录返回 true
     */
    public boolean hasSemester(String userId) {
        return getCurrentSemester(userId).isPresent();
    }

    /**
     * 获取用户當前星期几（1=周一 ~ 7=周日）
     */
    public int getCurrentDayOfWeek() {
        DayOfWeek dow = LocalDate.now().getDayOfWeek();
        return dow.getValue();
    }

    /**
     * 查询用户特定学年的已有学期记录
     *
     * <p>用于导入时判断同一学期是否已存在，避免重复创建。
     *
     * @param userId       用户标识
     * @param academicYear 学年（如 2026）
     * @param term         学期类型（SPRING / FALL）
     * @return 已有学期实体，不存在返回 empty
     */
    public Optional<SemesterEntity> findExistingSemester(String userId, int academicYear, String term) {
        return semesterRepository.findByUserIdAndTerm(userId, academicYear, term);
    }

    // ==================== 创建 ====================

    /**
     * 创建新学期
     *
     * <p>如果同一用户、同一学年和学期已存在，直接返回已有记录（幂等语义）。
     *
     * @param userId      用户标识
     * @param academicYear 学年，如 2026
     * @param term         学期类型：SPRING / FALL
     * @param startDate    第 1 周周一日期
     * @param source       来源：USER_CONFIRM / AUTO_DETECT / SYSTEM_DEFAULT
     * @return 保存后的学期实体（含 id）
     */
    public SemesterEntity createSemester(String userId, int academicYear, String term,
                                         LocalDate startDate, String source) {
        // 先检查是否已存在，避免违反唯一约束
        Optional<SemesterEntity> existing = findExistingSemester(userId, academicYear, term);
        if (existing.isPresent()) {
            log.info("学期已存在，直接返回已有记录 | userId={} | display={}", userId, existing.get().getDisplayName());
            return existing.get();
        }
        SemesterEntity semester = new SemesterEntity(userId, academicYear, term, startDate, source);
        return semesterRepository.save(semester);
    }

    /**
     * 创建新学期（基于 {@link SemesterEntity} 实体，含设置好的字段）
     *
     * @param semester 学期实体
     * @return 保存后的学期实体（含 id）
     */
    public SemesterEntity createSemester(SemesterEntity semester) {
        // 先检查是否已存在，避免违反唯一约束
        Optional<SemesterEntity> existing = findExistingSemester(
                semester.getUserId(), semester.getAcademicYear(), semester.getTerm());
        if (existing.isPresent()) {
            log.info("学期已存在，直接返回已有记录 | id={} | display={}", existing.get().getId(), existing.get().getDisplayName());
            return existing.get();
        }
        return semesterRepository.save(semester);
    }

    // ==================== 学期推断 ====================

    /**
     * 根据日期自动推算学期
     *
     * <p>规则：3月~8月 → 春季学期，以当年 3 月 1 日所在周一为第 1 周
     *       9月~次年2月 → 秋季学期，以当年 9 月 1 日所在周一为第 1 周
     *
     * @param userId 用户标识（用于设置 semester 的 userId）
     * @param date   参考日期
     * @return 学期实体（尚未持久化，不含 id）
     */
    public static SemesterEntity detectSemester(String userId, LocalDate date) {
        int month = date.getMonthValue();
        if (month >= 3 && month <= 8) {
            // 春季学期
            int year = date.getYear();
            LocalDate startDate = getMondayOfWeek(LocalDate.of(year, 3, 1));
            return new SemesterEntity(userId, year, SemesterEntity.TERM_SPRING,
                    startDate, SemesterEntity.SOURCE_AUTO_DETECT);
        } else {
            // 秋季学期：9月~次年2月，academicYear 取 9 月所在的年份
            int year = month >= 9 ? date.getYear() : date.getYear() - 1;
            LocalDate startDate = getMondayOfWeek(LocalDate.of(year, 9, 1));
            return new SemesterEntity(userId, year, SemesterEntity.TERM_FALL,
                    startDate, SemesterEntity.SOURCE_AUTO_DETECT);
        }
    }

    /**
     * 获取某一日所在周的周一
     *
     * @param date 参考日期
     * @return 所在周的周一
     */
    public static LocalDate getMondayOfWeek(LocalDate date) {
        int dow = date.getDayOfWeek().getValue(); // 1=Mon ~ 7=Sun
        return date.minusDays(dow - 1);
    }

    // ==================== 删除 ====================

    /**
     * 删除用户全部学期
     *
     * @param userId 用户标识
     */
    public void deleteAll(String userId) {
        semesterRepository.deleteByUserId(userId);
    }

    /**
     * 统计用户学期数量
     *
     * @param userId 用户标识
     * @return 学期数量
     */
    public int countSemesters(String userId) {
        return semesterRepository.countByUserId(userId);
    }
}