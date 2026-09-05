package com.youkeda.exercise.claw.feature.schedule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Objects;

/**
 * 课表查询与单课管理操作。
 *
 * <p>从 {@code CourseImportTool} 拆出的查询/管理分组委托类（批次 4）：
 * query_today / query_free_time / query_all / query_weekday（查询），
 * delete / update / clear（单课管理）。导入流程见 {@code CourseImportFlowActions}。
 */
@Component
public class CourseQueryActions {

    private static final Logger log = LoggerFactory.getLogger(CourseQueryActions.class);

    private static final String[] DAY_NAMES = {"", "周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    private final CourseService courseService;
    private final CourseRepository courseRepository;
    private final SemesterConfig semesterConfig;
    private final SemesterService semesterService;
    private final CourseMessageFormatter messageFormatter;
    private final ObjectMapper objectMapper;
    private final CourseImportStateManager importStateManager;
    private final ScheduleReminderService reminderService;

    public CourseQueryActions(CourseService courseService,
                              CourseRepository courseRepository,
                              SemesterConfig semesterConfig,
                              SemesterService semesterService,
                              CourseMessageFormatter messageFormatter,
                              ObjectMapper objectMapper,
                              CourseImportStateManager importStateManager,
                              ScheduleReminderService reminderService) {
        this.courseService = courseService;
        this.courseRepository = courseRepository;
        this.semesterConfig = semesterConfig;
        this.semesterService = semesterService;
        this.messageFormatter = messageFormatter;
        this.objectMapper = objectMapper;
        this.importStateManager = importStateManager;
        this.reminderService = reminderService;
    }

    public String handleQueryDate(JsonNode args, String userId) {
        try {
            String dateText = args.path("date").asText("");
            if (!dateText.matches("\\d{4}-\\d{2}-\\d{2}")) return errorJson("请提供具体日期 date（yyyy-MM-dd），例如先将明天换算为日期");
            return queryDate(userId, LocalDate.parse(dateText), "query_date");
        } catch (DateTimeParseException exception) {
            return errorJson("日期无效，请使用真实日历日期（yyyy-MM-dd）");
        }
    }

    private String queryDate(String userId, LocalDate date, String action) {
        CourseService.DateCourses schedule = courseService.getCoursesOnDate(userId, date);
        String message = !schedule.calendarConfigured() ? "请先设置学期第一周的起始日期，才能准确查询课程"
                : schedule.week() <= 0 ? "该日期对应的学期尚未开始"
                : date + "（" + DAY_NAMES[date.getDayOfWeek().getValue()] + "）共 " + schedule.courses().size() + " 条课程安排";
        try {
            ObjectNode result = (ObjectNode) objectMapper.readTree(buildQueryResult(action, schedule.courses(), message, schedule.week()));
            result.put("date", date.toString());
            result.put("status", schedule.calendarConfigured() ? "success" : "needs_semester");
            result.put("semester_id", schedule.semesterId());
            return result.toString();
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public String handleReminderStatus(String userId) {
        ObjectNode result = objectMapper.valueToTree(reminderService.getStatus(userId));
        result.put("action", "query_reminder_status");
        return result.toString();
    }

    public String handleQueryToday(String userId) {
        return queryDate(userId, LocalDate.now(), "query_today");
    }

    public String handleQueryFreeTime(String userId) {
        List<CourseService.TimeSlot> freeSlots = courseService.getFreeTimeSlots(userId);

        if (freeSlots.isEmpty()) {
            return "{\"action\":\"query_free_time\",\"slots\":[],\"message\":\"今天全天都有课，没有空闲时间 😅\"}";
        }

        ObjectNode result = objectMapper.createObjectNode();
        result.put("action", "query_free_time");
        var array = result.putArray("slots");
        for (CourseService.TimeSlot slot : freeSlots) {
            array.add(slot.display());
        }
        result.put("message", "今日空闲时间段共 " + freeSlots.size() + " 段");
        result.put("formatted", messageFormatter.formatFreeTimeSlots(userId, freeSlots));
        return result.toString();
    }

    public String handleQueryAll(String userId) {
        List<CourseEntity> allCourses = courseService.getAllCourses(userId);
        if (allCourses.isEmpty()) {
            // 课表为空：进入课表导入状态，使用户随后发送的课表文件/图片能被
            // 清理旧的待导入状态，下一次 Web 上传可重新开始导入流程。
            // 这样「查看课表发现是空 → 引导上传 → 发文件」整条链路可衔接。
            importStateManager.setWaitingFile(userId);
            log.info("课表为空，已进入课表导入状态 | userId={}", userId);
            return "{\"action\":\"query_all\",\"courses\":[],\"message\":\"你还没有导入课表，快上传课表文件或告诉我课程信息吧！\"}";
        }
        return buildQueryResult("query_all", allCourses,
                "共有 " + allCourses.size() + " 门课程", resolveCurrentWeek(userId));
    }

    public String handleQueryWeekday(JsonNode args, String userId) {
        int dayOfWeek = args.path("day_of_week").asInt(0);
        if (dayOfWeek < 1 || dayOfWeek > 7) {
            return "{\"error\":\"无效的 day_of_week 参数，请输入 1（周一）~ 7（周日）\"}";
        }

        LocalDate date = LocalDate.now().with(java.time.DayOfWeek.of(dayOfWeek));
        return queryDate(userId, date, "query_weekday");
    }

    public String handleDelete(JsonNode args, String userId) {
        long courseId = args.path("course_id").asLong(0);
        if (courseId <= 0) {
            return errorJson("请提供要删除的课程 ID（course_id 参数）");
        }

        CourseEntity course = courseService.findCourseById(courseId);
        if (course == null) {
            return "{\"action\":\"delete\",\"status\":\"error\",\"message\":\"未找到 ID 为 " + courseId + " 的课程\"}";
        }
        if (!userId.equals(course.getUserId())) {
            return "{\"action\":\"delete\",\"status\":\"error\",\"message\":\"无权删除该课程\"}";
        }

        boolean deleted = courseService.deleteCourse(courseId, userId);
        if (deleted) {
            return "{\"action\":\"delete\",\"status\":\"success\","
                    + "\"deleted_course\":\"" + course.getCourseName() + "\","
                    + "\"message\":\"已删除课程「" + course.getCourseName() + "」\"}";
        }
        return "{\"action\":\"delete\",\"status\":\"error\",\"message\":\"删除失败\"}";
    }

    public String handleUpdate(JsonNode args, String userId) {
        long courseId = args.path("course_id").asLong(0);
        if (courseId <= 0) {
            return errorJson("请提供要修改的课程 ID（course_id 参数）");
        }

        CourseEntity existing = courseService.findCourseById(courseId);
        if (existing == null) {
            return "{\"action\":\"update\",\"status\":\"error\",\"message\":\"未找到 ID 为 " + courseId + " 的课程\"}";
        }
        if (!userId.equals(existing.getUserId())) {
            return "{\"action\":\"update\",\"status\":\"error\",\"message\":\"无权修改该课程\"}";
        }

        ObjectNode before = courseDetails(existing);
        CourseEntity original = existing;
        existing = new CourseEntity(userId, original.getCourseName(), original.getTeacher(),
                original.getDayOfWeek(), original.getStartPeriod(), original.getEndPeriod(), original.getClassroom(),
                original.getStartWeek(), original.getEndWeek(), original.getWeekType());
        existing.setId(original.getId());
        existing.setSemesterId(original.getSemesterId());

        JsonNode coursesNode = args.get("courses");
        if (coursesNode == null || !coursesNode.isArray() || coursesNode.size() != 1 || !coursesNode.get(0).isObject() || coursesNode.get(0).isEmpty()) {
            return errorJson("请在 courses 中提供一条待修改记录的具体字段；按 course_id 修改，不按课程名覆盖");
        }
        if (coursesNode != null && coursesNode.isArray() && !coursesNode.isEmpty()) {
            JsonNode updateSrc = coursesNode.get(0);
            if (updateSrc.has("course_name")) existing.setCourseName(updateSrc.get("course_name").asText());
            if (updateSrc.has("teacher")) existing.setTeacher(updateSrc.get("teacher").asText());
            if (updateSrc.has("day_of_week")) existing.setDayOfWeek(updateSrc.get("day_of_week").asInt());
            if (updateSrc.has("start_period")) existing.setStartPeriod(updateSrc.get("start_period").asInt());
            if (updateSrc.has("end_period")) existing.setEndPeriod(updateSrc.get("end_period").asInt());
            if (updateSrc.has("classroom")) existing.setClassroom(updateSrc.get("classroom").asText());
            if (updateSrc.has("start_week")) existing.setStartWeek(updateSrc.get("start_week").asInt());
            if (updateSrc.has("end_week")) existing.setEndWeek(updateSrc.get("end_week").asInt());
            if (updateSrc.has("week_type")) existing.setWeekType(updateSrc.get("week_type").asText());
        }

        if (existing.getCourseName() == null || existing.getCourseName().isBlank()
                || existing.getDayOfWeek() < 1 || existing.getDayOfWeek() > 7
                || existing.getStartPeriod() < 1 || existing.getEndPeriod() < existing.getStartPeriod()
                || existing.getEndPeriod() > 30 || existing.getStartWeek() < 1
                || existing.getEndWeek() < existing.getStartWeek() || existing.getEndWeek() > 60
                || !List.of("ALL", "ODD", "EVEN").contains(existing.getWeekType())) {
            return errorJson("课程数据无效：需有名称，星期为1–7，节次为1–30且起止有序，周次为1–60且起止有序，单双周为ALL/ODD/EVEN");
        }
        CourseEntity candidate = existing;
        List<CourseEntity> conflicts = courseService.getAllCourses(userId).stream()
                .filter(course -> !Objects.equals(course.getId(), candidate.getId()))
                .filter(course -> Objects.equals(course.getSemesterId(), candidate.getSemesterId()))
                .filter(course -> CourseService.isTimeConflict(course, candidate)).toList();
        if (!conflicts.isEmpty()) {
            ObjectNode conflict = objectMapper.createObjectNode();
            conflict.put("action", "update");
            conflict.put("status", "conflict");
            conflict.put("message", "修改后与同学期其他课程冲突，尚未保存，请调整时间");
            var rows = conflict.putArray("conflicts");
            conflicts.forEach(course -> rows.add(courseDetails(course)));
            return conflict.toString();
        }
        boolean updated = courseService.updateCourse(existing);
        if (updated) {
            ObjectNode result = objectMapper.createObjectNode();
            result.put("action", "update");
            result.put("status", "success");
            result.put("course_id", existing.getId());
            result.put("course_name", existing.getCourseName());
            result.put("day", existing.getDayDisplay());
            result.put("period", existing.getPeriodDisplay());
            result.put("weeks", existing.getWeekDisplay());
            result.set("before", before);
            result.set("after", courseDetails(existing));
            result.set("reminder", objectMapper.valueToTree(reminderService.getStatus(userId)));
            result.put("reminder_sync", "后续扫描会读取更新后的课表，无需重复创建提醒任务");
            result.put("message", "已更新课程「" + existing.getCourseName() + "」");
            return result.toString();
        }
        return "{\"action\":\"update\",\"status\":\"error\",\"message\":\"更新失败\"}";
    }

    public String handleClear(String userId) {
        int count = courseService.getCourseCount(userId);
        if (count == 0) {
            return "{\"action\":\"clear\",\"status\":\"success\",\"message\":\"课表已经是空的啦～\"}";
        }

        courseService.deleteAll(userId);
        return "{\"action\":\"clear\",\"status\":\"success\","
                + "\"deleted_count\":" + count + ","
                + "\"message\":\"已清空全部 " + count + " 门课程。\"}";
    }

    private String buildQueryResult(String action, List<CourseEntity> courses, String message, int currentWeek) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("action", action);
        result.put("current_week", currentWeek);
        result.put("current_week_display", currentWeek > 0 ? "第" + currentWeek + "周" : "假期");

        var array = result.putArray("courses");
        for (CourseEntity c : courses) {
            ObjectNode item = array.addObject();
            item.put("course_id", c.getId());
            item.put("semester_id", c.getSemesterId());
            item.put("course_name", c.getCourseName());
            item.put("day", c.getDayDisplay());
            item.put("period", c.getPeriodDisplay());
            if (!c.getClassroom().isBlank()) item.put("classroom", c.getClassroom());
            if (!c.getTeacher().isBlank()) item.put("teacher", c.getTeacher());
            item.put("weeks", c.getWeekDisplay());
        }

        result.put("count", courses.size());
        result.put("message", message);

        // 嵌入预格式化的聊天展示文本
        String formatted = switch (action) {
            case "query_today" -> messageFormatter.formatTodayCourses(courses, currentWeek);
            case "query_all" -> messageFormatter.formatWeekOverview(courses, currentWeek);
            default -> message;
        };
        result.put("formatted", formatted);

        return result.toString();
    }

    private ObjectNode courseDetails(CourseEntity course) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("course_id", course.getId());
        result.put("semester_id", course.getSemesterId());
        result.put("course_name", course.getCourseName());
        result.put("day_of_week", course.getDayOfWeek());
        result.put("start_period", course.getStartPeriod());
        result.put("end_period", course.getEndPeriod());
        result.put("classroom", course.getClassroom());
        result.put("teacher", course.getTeacher());
        result.put("start_week", course.getStartWeek());
        result.put("end_week", course.getEndWeek());
        result.put("week_type", course.getWeekType());
        return result;
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

    private String errorJson(String message) {
        try {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("status", "error");
            node.put("error", message);
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            return "{\"error\":\"" + message + "\"}";
        }
    }
}
