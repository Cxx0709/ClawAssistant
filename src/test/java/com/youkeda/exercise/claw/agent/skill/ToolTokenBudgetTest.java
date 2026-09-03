package com.youkeda.exercise.claw.agent.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.exercise.claw.skill.SkillDefinition;
import com.youkeda.exercise.claw.skill.SkillsProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具数量与 token 预算统计（只记录风险，不实现动态 Tool Selector）。
 */
class ToolTokenBudgetTest {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void reportToolCountPerSkill() throws Exception {
        SkillsProperties props = bindSkillsProperties();

        int globalCount = props.getGlobalTools() != null ? props.getGlobalTools().size() : 0;
        Set<String> commonTools = props.getCommonCapabilityTools();
        int commonCount = commonTools != null ? commonTools.size() : 0;

        System.out.println("===== 工具数量统计 =====");
        System.out.println("globalTools:            " + globalCount + " — " + props.getGlobalTools());
        System.out.println("commonCapabilityTools:  " + commonCount + " — " + commonTools);
        System.out.println();

        int maxSkillTools = 0;
        String maxSkillName = "";
        for (var entry : props.getSkills().entrySet()) {
            SkillDefinition def = entry.getValue();
            int skillTools = def.allowedTools() != null ? def.allowedTools().size() : 0;
            int total = globalCount + commonCount + skillTools;
            System.out.printf("  %-22s skill=%-2d  total=%-2d  tools=%s%n",
                    entry.getKey(), skillTools, total, def.allowedTools());
            if (skillTools > maxSkillTools) {
                maxSkillTools = skillTools;
                maxSkillName = entry.getKey();
            }
        }

        int maxTotal = globalCount + commonCount + maxSkillTools;
        System.out.println();
        System.out.println("最大单次请求工具数: " + maxTotal
                + " (skill=" + maxSkillName + ", skill tools=" + maxSkillTools + ")");

        int estimatedTokensPerTool = 200;
        int estimatedTotalTokens = maxTotal * estimatedTokensPerTool;
        System.out.println("估算 tools 参数 token 开销: ~" + estimatedTotalTokens + " tokens"
                + " (" + maxTotal + " tools × ~" + estimatedTokensPerTool + " tokens/tool)");

        if (maxTotal > 30) {
            System.out.println("⚠ 风险提示：工具数量 >30，建议关注 LLM tool selection 准确性");
        }
        if (estimatedTotalTokens > 6000) {
            System.out.println("⚠ 风险提示：工具 schema 估算 token >6000，"
                    + "可能挤占 context window");
        }

        assertTrue(maxTotal < 50,
                "单次请求工具数不应超过 50（当前 " + maxTotal + "），否则应评估动态 Tool Selector");
    }

    @Test
    void estimateToolDefinitionTokenCost() {
        JsonNode params = objectMapper.createObjectNode()
                .put("type", "object")
                .set("properties", objectMapper.createObjectNode()
                        .set("city", objectMapper.createObjectNode()
                                .put("type", "string")
                                .put("description", "城市名称，例如北京")));
        String json = "{\"type\":\"function\",\"function\":{\"name\":\"weather_query\","
                + "\"description\":\"查询指定城市的实时天气\",\"parameters\":"
                + params.toString() + "}}";

        System.out.println("===== Tool Definition 大小估算 =====");
        System.out.println("示例 tool definition JSON 长度: " + json.length() + " chars");
        int estimatedTokens = json.length() / 3;
        System.out.println("估算 token 数: ~" + estimatedTokens + " tokens/tool (中文混合)");
        System.out.println("30 tools 估算: ~" + (estimatedTokens * 30) + " tokens");
    }

    private static SkillsProperties bindSkillsProperties() throws Exception {
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
