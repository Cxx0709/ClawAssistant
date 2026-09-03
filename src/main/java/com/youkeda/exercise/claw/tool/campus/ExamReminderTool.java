package com.youkeda.exercise.claw.tool.campus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.youkeda.exercise.claw.agent.runtime.AbstractTool;
import com.youkeda.exercise.claw.agent.runtime.ToolExecutionContext;
import com.youkeda.exercise.claw.agent.runtime.ToolRegistry;
import com.youkeda.exercise.claw.domain.campus.CampusConfig;
import com.youkeda.exercise.claw.feature.campus.store.CampusConfigStore;
import com.youkeda.exercise.claw.feature.campus.store.PendingAskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "campus.enabled", havingValue = "true")
public class ExamReminderTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(ExamReminderTool.class);

    private final CampusConfigStore configStore;
    private final PendingAskStore pendingAskStore;

    public ExamReminderTool(CampusConfigStore configStore,
                                 ToolRegistry functionRegistry,
                                 ObjectMapper objectMapper,
                                 PendingAskStore pendingAskStore) {
        super(functionRegistry, objectMapper);
        this.configStore = configStore;
        this.pendingAskStore = pendingAskStore;
    }

    @Override
    public String getName() {
        return "exam_reminder_setup";
    }

    @Override
    public String getDescription() {
        return "设置/修改考试提醒。包括：配置学校、班级、推送偏好。"
            + "当用户说'设置考试提醒'、'我是X学校X班的'、"
            + "'帮我关注考试通知'、'补考也提醒我'、'考试设置'时调用。";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode action = objectMapper.createObjectNode();
        action.put("type", "string");
        action.put("description", "操作类型：setup=首次设置, modify=修改, query=查询, disable=关闭, answer=回答是否需要提醒");
        action.set("enum", objectMapper.createArrayNode()
            .add("setup").add("modify").add("query").add("disable").add("answer"));

        return schema()
                .string("school", "学校名称，如'南京邮电大学'")
                .string("className", "班级号，如'B250405'")
                .raw("action", action, true)
                .string("noticeType", "考试通知类型，如 FINAL_EXAM、CET、RETAKE 等（action=answer 时需要）")
                .string("answer", "用户的回答：yes=需要提醒, no=不需要（action=answer 时需要）")
                .build();
    }

    @Override
    public String execute(String argumentsJson, ToolExecutionContext context) {
        try {
            JsonNode args = objectMapper.readTree(argumentsJson);
            String action = args.path("action").asText("query");

            return switch (action) {
                case "setup", "modify" -> handleSetup(args);
                case "query" -> handleQuery();
                case "disable" -> handleDisable();
                case "answer" -> handleAnswer(args);
                default -> "{\"status\":\"ERROR\",\"message\":\"未知操作: " + action + "\"}";
            };

        } catch (Exception e) {
            log.error("ExamReminderTool 执行失败", e);
            return "{\"status\":\"ERROR\",\"message\":\"执行失败: " + e.getMessage() + "\"}";
        }
    }

    private String handleSetup(JsonNode args) throws Exception {
        String school = args.path("school").asText("");
        String className = args.path("className").asText("");

        if (school.isBlank()) {
            return "{\"status\":\"ERROR\",\"message\":\"请提供学校名称\"}";
        }

        CampusConfig config = new CampusConfig();
        config.setSchool(school);
        config.setClassName(className);
        config.setEnabled(true);
        configStore.save(config);

        String result = objectMapper.writeValueAsString(Map.of(
            "status", "SUCCESS",
            "message", "已设置考试提醒！学校: " + school
                + (className.isBlank() ? "" : ", 班级: " + className)
                + "。每天08:00自动检查教务处通知，期末考试和四六级通知会直接推送给你。"
        ));
        log.info("考试提醒设置成功 | school={} | class={}", school, className);
        return result;
    }

    private String handleQuery() throws Exception {
        CampusConfig config = configStore.get();
        if (config == null) {
            return "{\"status\":\"SUCCESS\",\"message\":\"尚未设置考试提醒。你可以说'我是南邮B250405班的'来设置。\"}";
        }

        return objectMapper.writeValueAsString(Map.of(
            "status", "SUCCESS",
            "school", config.getSchool(),
            "className", config.getClassName(),
            "enabled", config.isEnabled(),
            "autoPushTypes", config.getPreferences().getAutoPushTypes()
        ));
    }

    private String handleDisable() throws Exception {
        CampusConfig config = configStore.get();
        if (config != null) {
            config.setEnabled(false);
            configStore.save(config);
        }
        return "{\"status\":\"SUCCESS\",\"message\":\"已关闭考试提醒。再次发送'设置考试提醒'重新开启。\"}";
    }

    private String handleAnswer(JsonNode args) throws Exception {
        String noticeType = args.path("noticeType").asText("");
        String answer = args.path("answer").asText("");

        if (noticeType.isBlank() || answer.isBlank()) {
            return "{\"status\":\"ERROR\",\"message\":\"请提供 noticeType 和 answer 参数\"}";
        }

        pendingAskStore.updateAnswer(noticeType, answer);
        log.info("用户回答了考试提醒询问 | noticeType={} | answer={}", noticeType, answer);

        if ("yes".equals(answer)) {
            CampusConfig config = configStore.get();
            if (config != null) {
                List<String> currentTypes = new ArrayList<>(config.getPreferences().getAutoPushTypes());
                if (!currentTypes.contains(noticeType)) {
                    currentTypes.add(noticeType);
                    config.getPreferences().setAutoPushTypes(currentTypes);
                    configStore.save(config);
                }
            }
        }

        return objectMapper.writeValueAsString(Map.of(
            "status", "SUCCESS",
            "message", "已记录你的回答"
        ));
    }
}
