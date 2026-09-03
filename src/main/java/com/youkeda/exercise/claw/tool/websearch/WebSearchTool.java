package com.youkeda.exercise.claw.tool.websearch;
import com.youkeda.exercise.claw.feature.websearch.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.youkeda.exercise.claw.agent.runtime.AbstractTool;
import com.youkeda.exercise.claw.agent.runtime.ToolExecutionContext;
import com.youkeda.exercise.claw.agent.runtime.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 网页搜索函数（LLM Function Calling 适配器）
 *
 * <p>包装 {@link SearchService}，使其可供 LLM 通过工具调用方式使用。
 * LLM 生成 {"query": "云南旅游最新政策"} 参数，本函数执行后返回结构化搜索结果。
 *
 * <p>返回结果包含：
 * <ul>
 *   <li>{@code answer} — AI 摘要，LLM 可直接引用来组织回答</li>
 *   <li>{@code results[]} — 搜索结果列表，每项含 title / url / content / score</li>
 * </ul>
 *
 * <p>定位：当 LLM 发现已有专业工具无法满足的信息需求时调用。
 */
@Component
public class WebSearchTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTool.class);

    private final SearchService searchService;

    public WebSearchTool(SearchService searchService,
                             ObjectMapper objectMapper,
                             ToolRegistry functionRegistry) {
        super(functionRegistry, objectMapper);
        this.searchService = searchService;
    }

    @Override
    public String getName() {
        return "web_search";
    }

    @Override
    public String getDescription() {
        return "互联网兜底搜索工具。旅游场景必须优先使用地图、天气、时间等专业工具；"
                + "只有专业工具返回 EMPTY、PARTIAL、ERROR，或需要查询其能力范围外的团队价格、"
                + "开放时间、预约政策、酒店接待、会议室、发票等信息时才调用。"
                + "搜索词只覆盖缺失内容，不得重复搜索专业工具已经确认的信息。";
    }

    @Override
    public JsonNode getParameters() {
        return schema()
                .string("query", "搜索关键词，简洁明确的中文关键词组合。如：云南旅游最新政策、丽江景区开放情况", true)
                .integer("count", "返回结果条数，默认5条，最多10条")
                .build();
    }

    @Override
    public String execute(String argumentsJson, ToolExecutionContext context) {
        try {
            JsonNode args = objectMapper.readTree(argumentsJson);
            JsonNode queryNode = args.get("query");
            if (queryNode == null) {
                return "{\"error\": \"缺少必填参数: query\"}";
            }

            String query = queryNode.asText();
            int count = args.has("count") ? args.get("count").asInt() : 5;

            log.info("WebSearchTool 执行 | query={} | count={}", query, count);

            String rawResult = searchService.search(query, count);
            JsonNode searchResult = objectMapper.readTree(rawResult);
            ObjectNode result = objectMapper.createObjectNode();
            result.put("source", "WEB_SEARCH");
            if (searchResult.has("error")) {
                result.put("status", "ERROR");
                result.set("data", searchResult);
                result.put("fallback_required", false);
            } else if (!searchResult.path("results").isArray()
                    || searchResult.path("results").isEmpty()) {
                result.put("status", "EMPTY");
                result.set("data", searchResult);
                result.put("fallback_required", false);
            } else {
                result.put("status", "SUCCESS");
                result.set("data", searchResult);
                result.put("fallback_required", false);
            }
            return objectMapper.writeValueAsString(result);

        } catch (Exception e) {
            log.error("WebSearchTool 执行失败 | args={} | error={}", argumentsJson, e.getMessage());
            ObjectNode result = objectMapper.createObjectNode();
            result.put("status", "ERROR");
            result.put("source", "WEB_SEARCH");
            result.put("error", e.getMessage());
            result.put("fallback_required", false);
            return result.toString();
        }
    }
}
