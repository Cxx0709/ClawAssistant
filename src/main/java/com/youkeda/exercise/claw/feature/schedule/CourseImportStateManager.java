package com.youkeda.exercise.claw.feature.schedule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 课程导入状态管理器
 *
 * <p>管理用户导入课表的多步骤流程状态。
 * 流程：WAITING_FILE → (收到文件/图片) → (检测学期) → WAITING_SEMESTER（必要时）→ WAITING_CONFIRM → (用户确认) → 完成
 *
 * <p>使用内存状态，重启后丢失（用户重试即可）。
 * 待确认的 {@link CourseEntity} 列表和 {@link SemesterEntity} 暂存于内存，确认后写入 SQLite。
 */
@Component
public class CourseImportStateManager {

    private static final Logger log = LoggerFactory.getLogger(CourseImportStateManager.class);

    /** 导入阶段 */
    public enum Phase {
        /** 无进行中的导入 */
        NONE,
        /** 等待用户上传课表文件/图片 */
        WAITING_FILE,
        /** 课表已解析但学期不确定，等待用户确认学期 */
        WAITING_SEMESTER,
        /** 课表已解析且学期已确定，等待用户确认导入 */
        WAITING_CONFIRM
    }

    /** 用户导入状态 */
    public record ImportState(
            Phase phase,
            String fileAnalysis
    ) {
        public static ImportState none() {
            return new ImportState(Phase.NONE, null);
        }
    }

    private final ConcurrentMap<String, ImportState> userStates = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<CourseEntity>> pendingCourses = new ConcurrentHashMap<>();
    /** 待确认的学期信息（未持久化，确认后写入 DB） */
    private final ConcurrentMap<String, SemesterEntity> pendingSemesters = new ConcurrentHashMap<>();

    // ==================== 状态管理 ====================

    public void setWaitingFile(String userId) {
        userStates.put(userId, new ImportState(Phase.WAITING_FILE, null));
        pendingCourses.remove(userId);
        pendingSemesters.remove(userId);
        log.debug("导入状态：等待文件 | userId={}", userId);
    }

    public void setWaitingSemester(String userId) {
        userStates.put(userId, new ImportState(Phase.WAITING_SEMESTER, null));
        log.debug("导入状态：等待学期确认 | userId={}", userId);
    }

    public void setWaitingConfirm(String userId, String fileAnalysis) {
        userStates.put(userId, new ImportState(Phase.WAITING_CONFIRM, fileAnalysis));
        log.debug("导入状态：等待确认 | userId={}", userId);
    }

    public void clear(String userId) {
        userStates.remove(userId);
        pendingCourses.remove(userId);
        pendingSemesters.remove(userId);
        log.debug("导入状态已清除 | userId={}", userId);
    }

    public Phase getPhase(String userId) {
        return userStates.getOrDefault(userId, ImportState.none()).phase();
    }

    public ImportState getState(String userId) {
        return userStates.getOrDefault(userId, ImportState.none());
    }

    // ==================== 待确认课程 ====================

    public void setPendingCourses(String userId, List<CourseEntity> courses) {
        pendingCourses.put(userId, List.copyOf(courses));
        log.debug("待确认课程已保存 | userId={} | count={}", userId, courses.size());
    }

    public List<CourseEntity> getPendingCourses(String userId) {
        return pendingCourses.getOrDefault(userId, List.of());
    }

    // ==================== 待确认学期 ====================

    /**
     * 保存待确认的学期信息（未持久化）
     *
     * @param userId   用户标识
     * @param semester 学期实体（不含 id，未持久化）
     */
    public void setPendingSemester(String userId, SemesterEntity semester) {
        pendingSemesters.put(userId, semester);
        log.debug("待确认学期已保存 | userId={} | display={} | startDate={}",
                userId, semester.getDisplayName(), semester.getStartDateString());
    }

    /**
     * 获取待确认的学期信息
     *
     * @param userId 用户标识
     * @return 学期实体，不存在返回 null
     */
    public SemesterEntity getPendingSemester(String userId) {
        return pendingSemesters.get(userId);
    }

    /**
     * 清除待确认的学期信息
     */
    public void clearPendingSemester(String userId) {
        pendingSemesters.remove(userId);
    }

    // ==================== 统计 ====================

    public int waitingFileCount() {
        return (int) userStates.values().stream()
                .filter(s -> s.phase() == Phase.WAITING_FILE)
                .count();
    }

    public int waitingSemesterCount() {
        return (int) userStates.values().stream()
                .filter(s -> s.phase() == Phase.WAITING_SEMESTER)
                .count();
    }

    public int waitingConfirmCount() {
        return (int) userStates.values().stream()
                .filter(s -> s.phase() == Phase.WAITING_CONFIRM)
                .count();
    }
}