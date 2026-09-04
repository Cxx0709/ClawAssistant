package com.youkeda.exercise.claw.feature.schedule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 课表导入流程操作（import → parse → confirm/cancel，含学期确认）。
 *
 * <p>从 {@code CourseImportTool} 拆出的导入分组委托类（批次 4）。
 * 仅处理与导入状态机相关的 action；查询/管理/学校操作见
 * {@code CourseQueryActions} / {@code CourseSchoolActions}。
 */
@Component
public class CourseImportFlowActions {

    private static final Logger log = LoggerFactory.getLogger(CourseImportFlowActions.class);

    private final CourseService courseService;
    private final CourseRepository courseRepository;
    private final SemesterConfig semesterConfig;
    private final CourseImportStateManager importStateManager;
    private final SemesterDetector semesterDetector;
    private final SemesterRepository semesterRepository;
    private final SemesterService semesterService;
    private final CourseMessageFormatter messageFormatter;
    private final ObjectMapper objectMapper;

    public CourseImportFlowActions(CourseService courseService,
                                   CourseRepository courseRepository,
                                   SemesterConfig semesterConfig,
                                   CourseImportStateManager importStateManager,
                                   SemesterDetector semesterDetector,
                                   SemesterRepository semesterRepository,
                                   SemesterService semesterService,
                                   CourseMessageFormatter messageFormatter,
                                   ObjectMapper objectMapper) {
        this.courseService = courseService;
        this.courseRepository = courseRepository;
        this.semesterConfig = semesterConfig;
        this.importStateManager = importStateManager;
        this.semesterDetector = semesterDetector;
        this.semesterRepository = semesterRepository;
        this.semesterService = semesterService;
        this.messageFormatter = messageFormatter;
        this.objectMapper = objectMapper;
    }

    public String handleStartImport(String userId) {
        int existingCount = courseService.getCourseCount(userId);

        importStateManager.setWaitingFile(userId);

        ObjectNode result = objectMapper.createObjectNode();
        result.put("action", "import");
        result.put("status", "waiting_file");
        String msg = existingCount > 0
                ? "已保留现有课表（共 " + existingCount + " 条记录）。请发送新课表截图、PDF或Excel文件，预览确认后才替换对应学期的课表。"
                : "请发送课表截图、PDF或Excel文件，我会帮你导入课表。";
        result.put("message", msg);
        if (existingCount > 0) {
            result.put("existing_count", existingCount);
        }
        return result.toString();
    }

    public String handleParse(JsonNode args, String userId) {
        CourseImportStateManager.Phase phase = importStateManager.getPhase(userId);
        if (phase == CourseImportStateManager.Phase.NONE) {
            log.debug("直接解析课表（无前置 import 状态）| userId={}", userId);
        }

        // 优先使用图片/文件路径已解析并暂存的完整结构化课程（含星期/节次/周次），
        // 避免 Agent 从对话上下文裸重建时丢失结构（历史上曾全部退化成"周一第1节"）。
        List<CourseEntity> pending = importStateManager.getPendingCourses(userId);
        SemesterEntity previousSemester = importStateManager.getPendingSemester(userId);
        if (args.has("courses")) importStateManager.clear(userId);
        String jsonStr;
        List<CourseEntity> courses;
        if (!pending.isEmpty() && !args.has("courses")) {
            courses = pending;
            jsonStr = null;
            log.info("parse 使用图片/文件解析的待确认课程 | userId={} | count={}", userId, pending.size());
        } else {
            JsonNode coursesNode = args.get("courses");
            if (coursesNode == null || !coursesNode.isArray() || coursesNode.isEmpty()) {
                return "{\"action\":\"parse\",\"status\":\"error\","
                        + "\"message\":\"请从对话上下文中的文件/图片分析结果里提取课程信息后重新调用。\"}";
            }

            jsonStr = coursesNode.toString();
            courses = courseService.parseOnly(userId, jsonStr);

            if (courses.isEmpty()) {
                return "{\"action\":\"parse\",\"status\":\"error\","
                        + "\"message\":\"无法从提供的数据中识别出有效的课程信息。请重新上传课表图片/文件；"
                        + "若使用文字导入，每条课程必须包含星期(day_of_week)和节次(start_period/end_period)。\"}";
            }
            if (courses.size() != coursesNode.size()) {
                importStateManager.clear(userId);
                ObjectNode review = objectMapper.createObjectNode();
                review.put("action", "parse");
                review.put("status", "needs_review");
                review.put("message", "部分课程缺少名称、星期或节次，尚未保存。请核对并补全所有记录后重新预览，不能忽略无法识别的课程。");
                return review.toString();
            }
        }

        // 内部冲突检测：检查新解析出的课程间是否有同天同时段冲突
        // 这种冲突通常表示 LLM 的 day_of_week 分配有误
        List<String> internalConflicts = detectInternalDayConflicts(courses);
        for (String conflict : internalConflicts) {
            log.warn("新导入课程间存在同天同时段冲突 | userId={} | {}", userId, conflict);
        }

        // 冲突检测（与已有课表）
        List<CourseService.ConflictInfo> conflicts = courseService.detectConflicts(userId, courses);

        // ====================  Semester 检测 ====================
        // 从 LLM 参数中尝试提取学期信息
        int academicYear = args.path("academic_year").asInt(0);
        String term = args.path("term").asText("");

        SemesterEntity detectedSemester = null;
        if (academicYear > 0 && !term.isBlank()) {
            detectedSemester = semesterDetector.detectFromParams(userId, academicYear, term);
        }
        if (detectedSemester == null && academicYear == 0 && term.isBlank()) {
            detectedSemester = previousSemester;
        }
        if (detectedSemester == null) {
            // LLM 未提供学期信息，尝试自动推算
            detectedSemester = semesterDetector.detectAuto(userId);
            if (detectedSemester != null) {
                log.info("parse 时自动推算学期 | userId={} | display={}",
                        userId, detectedSemester.getDisplayName());
            }
        }

        // 存储待确认的学期（未持久化）
        if (detectedSemester != null) {
            importStateManager.setPendingSemester(userId, detectedSemester);
        }
        importStateManager.setPendingCourses(userId, courses);
        importStateManager.setWaitingConfirm(userId, jsonStr);

        ObjectNode result = objectMapper.createObjectNode();
        result.put("action", "parse");
        result.put("status", "preview");
        result.put("count", courses.size());
        result.set("internal_conflicts", objectMapper.valueToTree(internalConflicts));

        int currentWeek = resolveCurrentWeek(userId);
        result.put("current_week", currentWeek);
        result.put("current_week_display", currentWeek > 0 ? "第" + currentWeek + "周" : "学期未开始");

        var array = result.putArray("courses");
        for (CourseEntity c : courses) {
            ObjectNode item = array.addObject();
            item.put("course_name", c.getCourseName());
            item.put("day_of_week", c.getDayOfWeek());
            item.put("start_period", c.getStartPeriod());
            item.put("end_period", c.getEndPeriod());
            item.put("start_week", c.getStartWeek());
            item.put("end_week", c.getEndWeek());
            item.put("week_type", c.getWeekType());
            item.put("day", c.getDayDisplay());
            item.put("period", c.getPeriodDisplay());
            item.put("weeks", c.getWeekDisplay());
            if (!c.getClassroom().isBlank()) item.put("classroom", c.getClassroom());
            if (!c.getTeacher().isBlank()) item.put("teacher", c.getTeacher());
        }

        // Semester 信息
        if (detectedSemester != null) {
            ObjectNode semesterInfo = result.putObject("semester");
            semesterInfo.put("academic_year", detectedSemester.getAcademicYear());
            semesterInfo.put("term", detectedSemester.getTerm());
            semesterInfo.put("start_date", detectedSemester.getStartDateString());
            semesterInfo.put("start_date_display", detectedSemester.getStartDateDisplay());
            semesterInfo.put("source", detectedSemester.getSource());
            semesterInfo.put("source_display", detectedSemester.getSourceDisplay());
            semesterInfo.put("display_name", detectedSemester.getDisplayName());
            result.put("semester_display", detectedSemester.getDisplayName()
                    + "（第1周：" + detectedSemester.getStartDateDisplay() + "）");
        }

        // 冲突信息
        if (!conflicts.isEmpty()) {
            ArrayNode conflictArray = result.putArray("conflicts");
            for (CourseService.ConflictInfo cf : conflicts) {
                ObjectNode item = conflictArray.addObject();
                item.put("existing_course", cf.existingCourse().getCourseName());
                item.put("new_course", cf.newCourse().getCourseName());
                item.put("description", cf.description());
            }
            result.put("warning", "检测到 " + conflicts.size() + " 个时间冲突，请核对。确认后替换目标学期的完整课表，其他学期保留。");
        }

        result.put("formatted_preview",
                messageFormatter.formatImportPreview(courses, conflicts, currentWeek));

        String conflictSuffix = conflicts.isEmpty() ? "" : "，" + conflicts.size() + " 个时间冲突";
        result.put("message", "已识别出以下 " + courses.size() + " 门课程" + conflictSuffix
                + "，尚未保存。确认后替换目标学期完整课表（无学期信息时仅替换未绑定学期记录），其他学期保留。请确认或取消。");
        return result.toString();
    }

    /**
     * 检测新解析的课程列表内部是否存在同天同时段冲突
     * <p>同一用户同一 day_of_week 的同一时间段出现多门课程 → day_of_week 很可能分配错误。
     * 只记录 warning，不修改数据。</p>
     */
    private List<String> detectInternalDayConflicts(List<CourseEntity> courses) {
        List<String> conflicts = new ArrayList<>();
        for (int i = 0; i < courses.size(); i++) {
            for (int j = i + 1; j < courses.size(); j++) {
                CourseEntity a = courses.get(i);
                CourseEntity b = courses.get(j);
                if (CourseService.isTimeConflict(a, b)) {
                    String desc = String.format("day=%d period=%d-%d: 「%s」与「%s」冲突",
                            a.getDayOfWeek(), a.getStartPeriod(), a.getEndPeriod(),
                            a.getCourseName(), b.getCourseName());
                    conflicts.add(desc);
                }
            }
        }
        return conflicts;
    }

    public String handleConfirm(String userId) {
        if (importStateManager.getPhase(userId) != CourseImportStateManager.Phase.WAITING_CONFIRM) {
            return "{\"action\":\"confirm\",\"status\":\"error\",\"message\":\"请先预览完整课表并确认学期，再确认保存。\"}";
        }
        List<CourseEntity> pending = importStateManager.getPendingCourses(userId);
        if (pending.isEmpty()) {
            return "{\"action\":\"confirm\",\"status\":\"error\","
                    + "\"message\":\"没有待确认的课程数据，请先上传课表。\"}";
        }

        List<CourseEntity> saved;
        SemesterEntity pendingSemester = importStateManager.getPendingSemester(userId);

        if (pendingSemester != null) {
            // 有学期信息：检查是否已存在同一学期 → 复用，避免重复创建
            SemesterEntity existing = semesterService.findExistingSemester(
                    userId, pendingSemester.getAcademicYear(), pendingSemester.getTerm()).orElse(null);
            SemesterEntity savedSemester;
            if (existing != null) {
                savedSemester = existing;
                log.info("学期已存在，复用 | id={} | display={}", savedSemester.getId(), savedSemester.getDisplayName());
            } else {
                savedSemester = semesterRepository.save(pendingSemester);
                log.info("学期已创建 | id={} | display={}", savedSemester.getId(), savedSemester.getDisplayName());
            }

            // 为每条课程绑定 semesterId 并按学期保存
            pending.forEach(c -> c.setSemesterId(savedSemester.getId()));
            saved = courseRepository.replaceAllBySemester(userId, savedSemester.getId(), pending);

            importStateManager.clear(userId);

            ObjectNode result = objectMapper.createObjectNode();
            result.put("action", "confirm");
            result.put("status", "success");
            result.put("count", saved.size());
            result.put("semester_id", savedSemester.getId());
            result.put("semester_display", savedSemester.getDisplayName());

            int currentWeek = resolveCurrentWeek(userId);
            result.put("current_week", currentWeek);
            result.put("current_week_display", currentWeek > 0 ? "第" + currentWeek + "周" : "学期未开始");

            result.put("message", "课表导入成功！共 " + saved.size() + " 门课程（"
                    + savedSemester.getDisplayName() + "）。"
                    + "你可以问我「今天有什么课」来查看今日课程。");
            return result.toString();
        } else {
            // 无学期信息：保持旧行为（兼容）
            saved = courseService.saveCourses(userId, pending);
            importStateManager.clear(userId);

            ObjectNode result = objectMapper.createObjectNode();
            result.put("action", "confirm");
            result.put("status", "success");
            result.put("count", saved.size());

            int currentWeek = resolveCurrentWeek(userId);
            result.put("current_week", currentWeek);
            result.put("current_week_display", currentWeek > 0 ? "第" + currentWeek + "周" : "学期未开始");

            result.put("message", "课表导入成功！共 " + saved.size() + " 门课程。"
                    + "你可以问我「今天有什么课」来查看今日课程。");
            return result.toString();
        }
    }

    public String handleCancel(String userId) {
        importStateManager.clear(userId);
        return "{\"action\":\"cancel\",\"status\":\"success\",\"message\":\"已取消课表导入。\"}";
    }

    /**
     * 用户确认系统自动检测的学期
     *
     * <p>学期信息已通过 handleParse 或 handler 保存为 pendingSemester，
     * 用户确认后转移到 WAITING_CONFIRM 状态，等待最终确认导入。
     */
    public String handleConfirmSemester(String userId) {
        if (importStateManager.getPhase(userId) != CourseImportStateManager.Phase.WAITING_SEMESTER) {
            return "{\"action\":\"confirm_semester\",\"status\":\"error\","
                    + "\"message\":\"当前状态不需要确认学期。\"}";
        }

        SemesterEntity pending = importStateManager.getPendingSemester(userId);
        if (pending == null) {
            return "{\"action\":\"confirm_semester\",\"status\":\"error\","
                    + "\"message\":\"没有待确认的学期信息，请先上传课表。\"}";
        }

        List<CourseEntity> pendingCourses = importStateManager.getPendingCourses(userId);
        if (pendingCourses.isEmpty()) {
            return "{\"action\":\"confirm_semester\",\"status\":\"error\","
                    + "\"message\":\"没有待确认的课程数据，请先上传课表。\"}";
        }

        // 学期信息不需要持久化（等 confirm 时一起处理），直接进入 WAITING_CONFIRM
        importStateManager.setWaitingConfirm(userId, "semester_confirmed");

        ObjectNode result = objectMapper.createObjectNode();
        result.put("action", "confirm_semester");
        result.put("status", "success");
        result.put("semester_display", pending.getDisplayName());
        result.put("start_date", pending.getStartDateString());
        result.put("course_count", pendingCourses.size());
        result.put("message", "已确认" + pending.getDisplayName() + "（第1周：" + pending.getStartDateDisplay() + "），"
                + "共 " + pendingCourses.size() + " 门课程等待导入。回复「确认」保存课表。");
        return result.toString();
    }

    /**
     * 用户手动指定学期信息
     *
     * <p>当系统无法自动检测学期时，用户可通过此操作指定学期。
     * 支持参数：academic_year、term、start_date（可选）
     */
    public String handleSetSemester(JsonNode args, String userId) {
        int academicYear = args.path("academic_year").asInt(0);
        String term = args.path("term").asText("");

        if (academicYear <= 0 || term.isBlank()) {
            return "{\"action\":\"set_semester\",\"status\":\"error\","
                    + "\"message\":\"请提供学年和学期信息，如：academic_year=2026, term=FALL\"}";
        }

        // 尝试从用户传入的学期参数检测
        SemesterEntity semester = semesterDetector.detectFromParams(userId, academicYear, term);

        // 如果用户提供了 start_date，则覆盖
        String startDateStr = args.path("start_date").asText("");
        if (semester != null && !startDateStr.isBlank()) {
            try {
                semester.setStartDateFromString(startDateStr);
                log.info("用户指定学期起始日期 | startDate={}", startDateStr);
            } catch (Exception e) {
                return "{\"action\":\"set_semester\",\"status\":\"error\","
                        + "\"message\":\"日期格式错误，请使用 yyyy-MM-dd 格式，如 2026-09-07\"}";
            }
        } else if (semester == null) {
            return "{\"action\":\"set_semester\",\"status\":\"error\","
                    + "\"message\":\"无法识别的学期参数，请提供正确的学年（如2026）和学期（SPRING/FALL）\"}";
        }

        // 保存待确认学期
        importStateManager.setPendingSemester(userId, semester);

        List<CourseEntity> pendingCourses = importStateManager.getPendingCourses(userId);

        // 如果已有待确认课程，直接进入 WAITING_CONFIRM
        if (!pendingCourses.isEmpty()) {
            importStateManager.setWaitingConfirm(userId, "semester_set");

            ObjectNode result = objectMapper.createObjectNode();
            result.put("action", "set_semester");
            result.put("status", "success");
            result.put("semester_display", semester.getDisplayName());
            result.put("start_date", semester.getStartDateString());
            result.put("course_count", pendingCourses.size());
            result.put("message", "已设置" + semester.getDisplayName() + "（第1周：" + semester.getStartDateDisplay() + "），"
                    + "共 " + pendingCourses.size() + " 门课程等待导入。回复「确认」保存课表。");
            return result.toString();
        } else {
            // 无待确认课程，进入 WAITING_SEMESTER
            importStateManager.setWaitingSemester(userId);

            ObjectNode result = objectMapper.createObjectNode();
            result.put("action", "set_semester");
            result.put("status", "semester_ready");
            result.put("semester_display", semester.getDisplayName());
            result.put("start_date", semester.getStartDateString());
            result.put("message", "已设置" + semester.getDisplayName() + "（第1周：" + semester.getStartDateDisplay() + "）。"
                    + "请发送课表文件或图片。");
            return result.toString();
        }
    }

    /**
     * 解析用户当前教学周
     *
     * <p>优先使用用户自身的 {@link SemesterService#getCurrentWeek(String)} 计算结果；
     * 无学期记录时回退 {@link SemesterConfig#getCurrentWeek()}。
     */
    private int resolveCurrentWeek(String userId) {
        int week = semesterService.getCurrentWeek(userId);
        if (week > 0) {
            return week;
        }
        return semesterConfig.getCurrentWeek();
    }
}
