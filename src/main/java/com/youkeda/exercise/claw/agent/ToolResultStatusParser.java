package com.youkeda.exercise.claw.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.exercise.claw.agent.model.ResultStatus;
import org.springframework.stereotype.Component;

@Component
public class ToolResultStatusParser {

    private final ObjectMapper objectMapper;

    public ToolResultStatusParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ResultStatus parse(String resultJson) {
        if (resultJson == null || resultJson.isBlank()) return ResultStatus.FAILED;
        try {
            JsonNode node = objectMapper.readTree(resultJson);
            if (node.has("error")) return ResultStatus.FAILED;
            String status = node.path("status").asText("SUCCESS").toUpperCase();
            return switch (status) {
                case "SUCCESS", "STARTED" -> ResultStatus.SUCCESS;
                case "PARTIAL" -> ResultStatus.PARTIAL;
                case "BLOCKED" -> ResultStatus.BLOCKED;
                default -> ResultStatus.FAILED;
            };
        } catch (Exception e) {
            // P0-4 fail-closed：解析失败 ≠ 工具失败（可能工具成功但格式变化），标记 UNKNOWN 而非 SUCCESS
            return ResultStatus.UNKNOWN;
        }
    }
}
