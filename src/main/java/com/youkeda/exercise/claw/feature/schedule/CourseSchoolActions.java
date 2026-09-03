package com.youkeda.exercise.claw.feature.schedule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * 课表学校绑定操作。
 *
 * <p>从 {@code CourseImportTool} 拆出的学校分组委托类（批次 4）：
 * query_school / set_school / list_schools。导入流程见 {@code CourseImportFlowActions}，
 * 查询/管理见 {@code CourseQueryActions}。
 */
@Component
public class CourseSchoolActions {

    private final SchoolService schoolService;
    private final ObjectMapper objectMapper;

    public CourseSchoolActions(SchoolService schoolService, ObjectMapper objectMapper) {
        this.schoolService = schoolService;
        this.objectMapper = objectMapper;
    }

    /**
     * 查询用户当前绑定的学校信息
     */
    public String handleQuerySchool(String userId) {
        var school = schoolService.getUserSchool(userId);
        if (school == null) {
            return "{\"action\":\"query_school\",\"bound\":false,\"message\":\"你还没有绑定学校。"
                    + "为了准确计算课程时间，请告诉我你的学校名称。\"}";
        }

        try {
            ObjectNode result = objectMapper.createObjectNode();
            result.put("action", "query_school");
            result.put("bound", true);
            result.put("school_id", school.getId());
            result.put("school_name", school.getSchoolName());
            result.put("school_code", school.getSchoolCode() != null ? school.getSchoolCode() : "");
            result.put("message", "当前绑定学校：" + school.getSchoolName());
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            return "{\"action\":\"query_school\",\"bound\":true,\"school_name\":\""
                    + school.getSchoolName() + "\"}";
        }
    }

    /**
     * 为用户绑定学校
     */
    public String handleSetSchool(JsonNode args, String userId) {
        String schoolName = args.path("school_name").asText("");
        if (schoolName.isBlank()) {
            return "{\"action\":\"set_school\",\"status\":\"error\","
                    + "\"message\":\"请提供学校名称（school_name 参数），如「无锡学院」。\"}";
        }

        var school = schoolService.bindUserToSchoolByName(userId, schoolName);
        if (school == null) {
            return "{\"action\":\"set_school\",\"status\":\"error\","
                    + "\"message\":\"学校绑定失败，请稍后重试。\"}";
        }

        try {
            ObjectNode result = objectMapper.createObjectNode();
            result.put("action", "set_school");
            result.put("status", "success");
            result.put("school_id", school.getId());
            result.put("school_name", school.getSchoolName());
            result.put("message", "已绑定学校：" + school.getSchoolName()
                    + "，现在可以导入课表了。");
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            return "{\"action\":\"set_school\",\"status\":\"success\",\"school_name\":\""
                    + school.getSchoolName() + "\"}";
        }
    }

    /**
     * 列出可用学校列表
     */
    public String handleListSchools() {
        var presetNames = schoolService.listPresetSchoolNames();
        var allSchools = schoolService.listAllSchools();

        try {
            ObjectNode result = objectMapper.createObjectNode();
            result.put("action", "list_schools");

            var presetArray = result.putArray("preset_schools");
            for (String name : presetNames) {
                presetArray.add(name);
            }

            var dbArray = result.putArray("all_schools");
            for (var school : allSchools) {
                ObjectNode item = dbArray.addObject();
                item.put("id", school.getId());
                item.put("name", school.getSchoolName());
                item.put("code", school.getSchoolCode() != null ? school.getSchoolCode() : "");
            }

            result.put("message", "系统内置 " + presetNames.size() + " 套学校作息模板，"
                    + "数据库中共 " + allSchools.size() + " 所学校。"
                    + "可使用 set_school 操作绑定学校。");
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            return "{\"action\":\"list_schools\",\"preset_schools\":"
                    + presetNames.toString() + "}";
        }
    }
}
