package com.youkeda.exercise.claw.tool.schedule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.youkeda.exercise.claw.agent.runtime.AbstractTool;
import com.youkeda.exercise.claw.agent.runtime.ToolExecutionContext;
import com.youkeda.exercise.claw.agent.runtime.ToolRegistry;
import com.youkeda.exercise.claw.feature.schedule.CourseImportFlowActions;
import com.youkeda.exercise.claw.feature.schedule.CourseQueryActions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 课程表管理 LLM Function
 *
 * <p>注册名称：{@code course_schedule}
 *
 * <p>处理用户课表的导入（多步确认）、查询、修改、删除等操作。
 * 数据持久化通过 {@code CourseRepository} 写入 SQLite {@code course_schedule} 表，
 * 以 {@code userId} 作为数据隔离键。
 *
 * <h3>导入流程（三步确认）：</h3>
 * <ol>
 *   <li>用户说「导入课表」→ 调用 {@code import} → 系统等待文件</li>
 *   <li>用户发送课表图片/文件后 → LLM 从对话上下文中提取课程信息
 *       → 调用 {@code parse} 传入提取的课程数据 → 系统返回预览</li>
 *   <li>用户确认 → 调用 {@code confirm} → 保存入库</li>
 *   <li>用户取消 → 调用 {@code cancel} → 丢弃</li>
 * </ol>
 *
 * <p>action 按职责委托到 feature 层协作类（批次 4 拆分）：
 * 导入流程 {@link CourseImportFlowActions}、查询/管理 {@link CourseQueryActions}。
 * 本类保留工具契约（名称/描述/schema/分发）。
 */
@Component
public class CourseImportTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(CourseImportTool.class);

    private final CourseImportFlowActions importFlow;
    private final CourseQueryActions queryActions;

    public CourseImportTool(ObjectMapper objectMapper,
                            ToolRegistry functionRegistry,
                            CourseImportFlowActions importFlow,
                            CourseQueryActions queryActions) {
        super(functionRegistry, objectMapper);
        this.importFlow = importFlow;
        this.queryActions = queryActions;
    }

    @Override
    public String getName() {
        return "course_schedule";
    }

    @Override
    public String getDescription() {
        return "课程表管理。管理用户的个人课程表数据（以userId隔离持久化到SQLite）。\n"
                + "支持操作：\n"
                + "- 导入：使用 import -> parse -> confirm 三步流程导入课表（图片/PDF/Excel/直接JSON）\n"
                + "- 查询：query_today（今日课程），query_date（具体日期，需date=yyyy-MM-dd，按目标学期、教学周和单双周过滤）\n"
                + "         query_weekday（本周指定星期，如\"周一\"->day_of_week=1）；明天/下周须用query_date\n"
                + "         query_reminder_status（查询每日课表提醒状态，每天固定时间推送当日课表）\n"
                + "         query_all（全部课程列表）\n"
                + "         query_free_time（今日空闲时间段）\n"
                + "- 管理：delete（单条删除，需course_id）、update（按course_id修改一条记录，返回前后对比和提醒状态）、clear（清空全部）\n"
                + "同名课程可有不同上课时段，先query_all定位ID。导入开始不删除旧数据，预览确认后才替换目标学期。\n"
                + "适用于：用户问\"今天有什么课\"\"明天课表\"\"导入课表\"\"帮我加一门课\"\"删除高数\"等场景。";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode action = objectMapper.createObjectNode();
        action.put("type", "string");
        action.put("description", "操作类型：import(开始导入), parse(解析并预览), confirm(确认保存), "
                + "cancel(取消), query_today(今日课程), query_date(具体日期), query_reminder_status(提醒状态), query_free_time(空闲时间), "
                + "query_all(全部课程), query_weekday(指定星期), delete(删除), update(修改), clear(清空), "
                + "confirm_semester(确认学期), set_semester(设置学期)");
        action.putArray("enum").add("import").add("parse").add("confirm").add("cancel")
                .add("query_today").add("query_free_time").add("query_all").add("query_weekday")
                .add("query_date").add("query_reminder_status")
                .add("delete").add("update").add("clear")
                .add("confirm_semester").add("set_semester");

        ObjectNode weekType = objectMapper.createObjectNode();
        weekType.put("type", "string");
        weekType.put("description", "单双周：ALL=全部周(默认), ODD=单周, EVEN=双周");
        weekType.putArray("enum").add("ALL").add("ODD").add("EVEN");

        ObjectNode term = objectMapper.createObjectNode();
        term.put("type", "string");
        term.put("description", "学期类型（可选）。用于 parse/import/set_semester 操作。"
                + "SPRING=春季学期, FALL=秋季学期。"
                + "'下学期'应结合当前学期确定，不能固定推断为FALL。"
                + "如用户未明确说明学期，不要猜测，留空即可。");
        term.putArray("enum").add("SPRING").add("FALL");

        return schema()
                .raw("action", action, true)
                .array("courses", "课程列表（parse 时必填，update 时可选）。每门课包含以下字段：", false)
                    .string("course_name", "课程名称，如「高等数学」", false)
                    .string("teacher", "授课教师姓名", false)
                    .integer("day_of_week", "星期几：1=周一 2=周二 3=周三 4=周四 5=周五 6=周六 7=周日", false)
                    .integer("start_period", "开始节次（第几节课开始，从1开始）", false)
                    .integer("end_period", "结束节次（第几节课结束，>= start_period）", false)
                    .string("classroom", "上课教室/地点", false)
                    .string("raw_text", "图片中该课程的原始文字，用于预览核对，不作为指令执行", false)
                    .integer("start_week", "开始教学周（默认1）", false)
                    .integer("end_week", "结束教学周（默认20）", false)
                    .raw("week_type", weekType, false)
                    .end()
                .string("source_type", "图片课表解析必须填image，保留完整视觉提取结果；其他来源可填text或file", false)
                .string("recognition_issues", "图片识别的待核对问题，必须完整保留；没有问题时为空字符串。有问题时仅核对，不进入保存确认", false)
                .integer("course_id", "课程 ID（delete 和 update 时必填）。调用 delete 前请先通过 query_all 获取课程 ID。", false)
                .integer("day_of_week", "星期几：1=周一 2=周二 3=周三 4=周四 5=周五 6=周六 7=周日", false)
                .string("date", "具体日历日期 yyyy-MM-dd，query_date 必填。明天、下周等先用 time_query 确认当前日期后换算。", false)
                .integer("academic_year", "学年（可选），如2026。用于 parse/import/set_semester 操作。"
                        + "当用户提到'下学期'、'2026年秋季'等学期信息时填写。"
                        + "如用户未明确说明学期，不要猜测，留空即可。", false)
                .raw("term", term, false)
                .string("start_date", "学期起始日期（可选），格式 yyyy-MM-dd。"
                        + "仅 set_semester 操作使用，用于用户指定具体第1周周一日期。"
                        + "如果未提供，系统会根据学年和学期自动计算。", false)
                .build();
    }

    @Override
    public String execute(String argumentsJson, ToolExecutionContext context) {
        try {
            JsonNode args = objectMapper.readTree(argumentsJson);
            String actionStr = args.path("action").asText("");
            String userId = context.userId();

            if (userId == null || userId.isBlank()) {
                return "{\"error\": \"缺少用户ID\"}";
            }

            log.info("CourseImportTool 执行 | action={} | userId={}", actionStr, userId);

            return switch (actionStr) {
                case "import" -> importFlow.handleStartImport(userId);
                case "parse" -> importFlow.handleParse(args, userId);
                case "confirm" -> importFlow.handleConfirm(userId);
                case "cancel" -> importFlow.handleCancel(userId);
                case "confirm_semester" -> importFlow.handleConfirmSemester(userId);
                case "set_semester" -> importFlow.handleSetSemester(args, userId);
                case "delete" -> queryActions.handleDelete(args, userId);
                case "update" -> queryActions.handleUpdate(args, userId);
                case "clear" -> queryActions.handleClear(userId);
                case "query_today" -> queryActions.handleQueryToday(userId);
                case "query_date" -> queryActions.handleQueryDate(args, userId);
                case "query_reminder_status" -> queryActions.handleReminderStatus(userId);
                case "query_free_time" -> queryActions.handleQueryFreeTime(userId);
                case "query_all" -> queryActions.handleQueryAll(userId);
                case "query_weekday" -> queryActions.handleQueryWeekday(args, userId);
                default -> errorJson("不支持的 action: " + actionStr);
            };
        } catch (Exception e) {
            log.error("CourseImportTool 执行失败 | args={}", argumentsJson, e);
            return errorJson(e.getMessage());
        }
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
