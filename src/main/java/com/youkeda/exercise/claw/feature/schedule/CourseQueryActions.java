package com.youkeda.exercise.claw.feature.schedule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 课表查询与单课管理操作。
 *
 * <p>从 {@code CourseImportTool} 拆出的查询/管理分组委托类（批次 4）：
 * query_today / query_free_time / query_all / query_weekday（查询），
 * delete / update / clear（单课管理）。导入流程见 {@code CourseImportFlowActions}，
 * 学校操作见 {@code CourseSchoolActions}。
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

    public CourseQueryActions(CourseService courseService,
                              CourseRepository courseRepository,
                              SemesterConfig semesterConfig,
                              SemesterService semesterService,
                              CourseMessageFormatter messageFormatter,
                              ObjectMapper objectMapper,
                              CourseImportStateManager importStateManager) {
        this.courseService = courseService;
        this.courseRepository = courseRepository;
        this.semesterConfig = semesterConfig;
        this.semesterService = semesterService;
        this.messageFormatter = messageFormatter;
        this.objectMapper = objectMapper;
        this.importStateManager = importStateManager;
    }

    public String handleQueryToday(String userId) {
        int currentWeek = resolveCurrentWeek(userId);
        if (currentWeek <= 0) {
            return buildQueryResult("query_today", List.of(),
                    "学期尚未开始（当前日期早于学期起始日）", currentWeek);
        }

        List<CourseEntity> todayCourses = courseService.getTodayCourses(userId);

        if (todayCourses.isEmpty()) {
            List<CourseEntity> allDayCourses = courseRepository.findByUserIdAndDay(
                    userId, semesterService.getCurrentDayOfWeek());
            if (allDayCourses.isEmpty()) {
                return buildQueryResult("query_today", List.of(),
                        "今天没有安排课程，好好休息吧！😊", currentWeek);
            } else {
                return buildQueryResult("query_today", List.of(),
                        "今天虽然有课，但不在当前教学周，所以没有课程安排。当前是第 " + currentWeek + " 周。", currentWeek);
            }
        }

        return buildQueryResult("query_today", todayCourses,
                "今日课程共 " + todayCourses.size() + " 门", currentWeek);
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

        int currentWeek = resolveCurrentWeek(userId);
        if (currentWeek <= 0) {
            return buildQueryResult("query_weekday", List.of(),
                    "学期尚未开始", currentWeek);
        }

        List<CourseEntity> courses = courseService.getCoursesByDay(userId, dayOfWeek);
        return buildQueryResult("query_weekday", courses,
                DAY_NAMES[dayOfWeek] + "共 " + courses.size() + " 门课", currentWeek);
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

        JsonNode coursesNode = args.get("courses");
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
            node.put("error", message);
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            return "{\"error\":\"" + message + "\"}";
        }
    }
}
