package com.youkeda.exercise.claw.agent.skill;

import com.youkeda.exercise.claw.skill.SkillDefinition;
import com.youkeda.exercise.claw.skill.SkillsProperties;
import com.youkeda.exercise.claw.skill.SkillExecutionMode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillsPropertiesYamlBindingTest {

    @Test
    void bindsKnowledgeGlobalSwitchAsEnabledByDefault() throws Exception {
        SkillsProperties properties = bindSkillsProperties();

        assertNotNull(properties.getKnowledge());
        assertTrue(properties.getKnowledge().isGlobalEnabled());
    }

    @Test
    void bindsBackgroundWorkflowExecutionMetadataFromYaml() throws Exception {
        SkillsProperties properties = bindSkillsProperties();
        SkillDefinition scout = properties.getSkills().get("information-scout");

        assertNotNull(scout);
        assertNotNull(scout.execution());
        assertEquals(SkillExecutionMode.BACKGROUND_WORKFLOW, scout.execution().mode());
        assertEquals("informationScoutSkillExecutor", scout.execution().executorName());
        assertEquals("scoutWorkflow",
                properties.getSkillWorkflowBindings().get("information-scout"));
    }

    @Test
    void bindsCampusSkillWithScheduleToolsFromYaml() throws Exception {
        SkillsProperties properties = bindSkillsProperties();
        SkillDefinition campus = properties.getSkills().get("campus");

        assertNotNull(campus, "campus 技能必须在 skills.yml 中定义，否则「查看课表」无法路由到 course_schedule");
        assertEquals("prompts/skills/campus.txt", campus.systemPromptResource());
        Set<String> tools = campus.allowedTools();
        assertTrue(tools.contains("course_schedule"));
        assertTrue(tools.contains("exam_schedule"));
        assertTrue(tools.contains("exam_reminder_setup"));
    }

    @Test
    void bindsCommonCapabilityToolsFromYaml() throws Exception {
        SkillsProperties properties = bindSkillsProperties();
        Set<String> commonCapTools = properties.getCommonCapabilityTools();

        assertNotNull(commonCapTools, "common-capability-tools 必须在 skills.yml 中定义");
        assertTrue(commonCapTools.contains("create_schedule_task"),
                "create_schedule_task 必须是通用能力工具，否则「设置提醒」永远不会真正创建定时任务");
        assertTrue(commonCapTools.contains("list_schedule_tasks"),
                "list_schedule_tasks 必须是通用能力工具，否则「我有哪些提醒」LLM 无工具可查，只能编造");
        assertTrue(commonCapTools.contains("update_schedule_task"),
                "update_schedule_task 必须是通用能力工具，否则「修改提醒」无法生效");
        assertTrue(commonCapTools.contains("cancel_schedule_task"),
                "cancel_schedule_task 必须是通用能力工具，否则「取消提醒」无法生效");
        assertTrue(commonCapTools.contains("web_search"),
                "web_search 必须是通用能力工具，否则各 skill 无法使用搜索能力");
        assertTrue(commonCapTools.contains("file_generate"),
                "file_generate 必须是通用能力工具");
        assertTrue(commonCapTools.contains("file_read"),
                "file_read 必须是通用能力工具");
        assertTrue(commonCapTools.contains("file_search"),
                "file_search 必须是通用能力工具");
        assertTrue(commonCapTools.contains("image_generate"),
                "image_generate 必须是通用能力工具");
        assertTrue(commonCapTools.contains("text_to_speech"),
                "text_to_speech 必须是通用能力工具");
    }

    @Test
    void bindsCommonSkillWithNoRedundantCapabilityToolsFromYaml() throws Exception {
        SkillsProperties properties = bindSkillsProperties();
        SkillDefinition common = properties.getSkills().get("common");

        assertNotNull(common);
        // common skill no longer lists capability tools — they come from commonCapabilityTools
        Set<String> tools = common.allowedTools();
        assertTrue(tools.isEmpty() || !tools.contains("web_search"),
                "common skill 的 optionalTools 不应再包含 web_search（已提升到 common-capability-tools）");
    }

    @Test
    void bindsTravelTriggerPolicyNameFromYaml() throws Exception {
        SkillsProperties properties = bindSkillsProperties();
        SkillDefinition travel = properties.getSkills().get("travel");
        assertNotNull(travel);
        assertEquals("travelTriggerPolicy", travel.triggerPolicyName(),
                "travel 必须声明 custom triggerPolicy，否则「去 Bali 玩五天」这类非中文目的地请求无法触发");
    }

    @Test
    void bindsTransportSkillWithRideToolFromYaml() throws Exception {
        SkillsProperties properties = bindSkillsProperties();
        SkillDefinition transport = properties.getSkills().get("transport");

        assertNotNull(transport);
        assertTrue(transport.allowedTools().contains("didi_ride"),
                "transport 必须声明 didi_ride，且 DidiRideTool.getName() 必须一致（见 DidiRideToolTest）");
        assertTrue(transport.allowedTools().contains("transport_recommend"));
    }

    @Test
    void bindsImageSkillWithGenerateToolFromYaml() throws Exception {
        SkillsProperties properties = bindSkillsProperties();
        SkillDefinition image = properties.getSkills().get("image");

        assertNotNull(image, "image 技能必须在 skills.yml 中定义，否则「生成图片」无法路由到 image_generate");
        assertTrue(image.allowedTools().contains("image_generate"),
                "image 必须声明 image_generate，且 ImageGenerationTool.getName() 必须一致");
    }

    private SkillsProperties bindSkillsProperties() throws Exception {
        MutablePropertySources sources = new MutablePropertySources();
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        for (PropertySource<?> source : loader.load(
                "skills", new ClassPathResource("config/skills.yml"))) {
            sources.addLast(source);
        }
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(sources.iterator().next());

        return Binder.get(environment)
                .bind("claw", Bindable.of(SkillsProperties.class))
                .orElseThrow(() -> new IllegalStateException("skills.yml bind failed"));
    }
}
