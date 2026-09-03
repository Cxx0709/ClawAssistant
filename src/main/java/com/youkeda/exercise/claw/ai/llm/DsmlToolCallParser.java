package com.youkeda.exercise.claw.ai.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 兼容部分 OpenAI 协议代理把工具调用放进 content 的 DSML 格式。
 */
final class DsmlToolCallParser {

    private static final Logger log = LoggerFactory.getLogger(DsmlToolCallParser.class);
    private static final String PREFIX = "｜｜DSML｜｜";

    private static final Pattern INVOKE_PATTERN = Pattern.compile(
            "<" + PREFIX + "invoke\\s+name=\"([^\"]+)\"\\s*>(.*?)</"
                    + PREFIX + "invoke\\s*>",
            Pattern.DOTALL);

    private static final Pattern PARAMETER_PATTERN = Pattern.compile(
            "<" + PREFIX + "parameter\\s+name=\"([^\"]+)\""
                    + "(?:\\s+string=\"(true|false)\")?\\s*>(.*?)</"
                    + PREFIX + "parameter\\s*>",
            Pattern.DOTALL);

    private DsmlToolCallParser() {
    }

    static boolean containsMarkup(String content) {
        return content != null && content.contains(PREFIX);
    }

    static List<LLMResponse.ToolCall> parse(String content, ObjectMapper objectMapper) {
        if (!containsMarkup(content)) {
            return List.of();
        }

        List<LLMResponse.ToolCall> calls = new ArrayList<>();
        Matcher invokeMatcher = INVOKE_PATTERN.matcher(content);
        while (invokeMatcher.find()) {
            String toolName = decodeEntities(invokeMatcher.group(1)).trim();
            String body = invokeMatcher.group(2);
            ObjectNode arguments = objectMapper.createObjectNode();

            Matcher parameterMatcher = PARAMETER_PATTERN.matcher(body);
            while (parameterMatcher.find()) {
                String name = decodeEntities(parameterMatcher.group(1)).trim();
                boolean forceString = "true".equalsIgnoreCase(parameterMatcher.group(2));
                String rawValue = decodeEntities(parameterMatcher.group(3)).trim();
                putValue(arguments, name, rawValue, forceString, objectMapper);
            }

            if (!toolName.isBlank()) {
                calls.add(new LLMResponse.ToolCall(
                        "dsml_call_" + UUID.randomUUID(),
                        "function",
                        toolName,
                        arguments.toString()));
            }
        }
        return calls;
    }

    private static void putValue(ObjectNode arguments,
                                 String name,
                                 String rawValue,
                                 boolean forceString,
                                 ObjectMapper objectMapper) {
        if (forceString) {
            arguments.put(name, rawValue);
            return;
        }

        try {
            JsonNode value = objectMapper.readTree(rawValue);
            if (value != null) {
                arguments.set(name, value);
                return;
            }
        } catch (Exception e) {
            log.debug("DSML 参数不是合法 JSON，按字符串处理 | name={}", name);
        }
        arguments.put(name, rawValue);
    }

    private static String decodeEntities(String value) {
        return value
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&");
    }
}
