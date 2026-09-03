package com.youkeda.exercise.claw.tool.transport;
import com.youkeda.exercise.claw.feature.transport.TransportService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.youkeda.exercise.claw.agent.runtime.AbstractTool;
import com.youkeda.exercise.claw.agent.runtime.ToolExecutionContext;
import com.youkeda.exercise.claw.agent.runtime.ToolRegistry;
import com.youkeda.exercise.claw.domain.transport.TransportRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 交通方式推荐函数（LLM Function Calling 适配器）
 *
 * <p>将交通方式推荐能力以 LLM Function Calling 方式暴露给 ReActAgentExecutor。
 * 注册函数：{@code transport_recommend}。
 *
 * <p>函数被调用后自动由 {@link ToolRegistry} 管理，
 * ReActAgentExecutor 在 tool-calling 循环中自动发现并调用。
 *
 * <p>核心链路：
 * <ol>
 *   <li>LLM 提取参数：from、to、people、budget</li>
 *   <li>调用 {@link TransportService#recommend} 执行推荐</li>
 *   <li>返回结构化 JSON 结果</li>
 * </ol>
 */
@Component
public class TransportRecommendTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(TransportRecommendTool.class);

    private final TransportService transportService;

    public TransportRecommendTool(TransportService transportService,
                                       ObjectMapper objectMapper,
                                       ToolRegistry functionRegistry) {
        super(functionRegistry, objectMapper);
        this.transportService = transportService;
    }

    @Override
    public String getName() {
        return "transport_recommend";
    }

    @Override
    public String getDescription() {
        return "根据出发地、目的地、人数，对比不同交通方式（大巴、自驾、高铁、飞机）的成本和时间，"
                + "并推荐适合团建的交通方案。"
                + "适合用户问「怎么去」「交通方式」「团建出行」时使用。"
                + "返回各方式的总费用、人均费用、耗时和综合推荐。";
    }

    @Override
    public JsonNode getParameters() {
        return schema()
                .string("from", "出发地，城市名称或具体地址，如：上海、杭州、北京", true)
                .string("to", "目的地，城市名称或具体地址，如：杭州、南京、苏州", true)
                .integer("people", "出行人数，如：30、5、100", true)
                .integer("budget", "可选总预算（元），如：50000。不填则不考虑预算限制", false)
                .build();
    }

    @Override
    public String execute(String argumentsJson, ToolExecutionContext context) {
        try {
            JsonNode args = objectMapper.readTree(argumentsJson);

            String from = args.path("from").asText("");
            String to = args.path("to").asText("");
            int people = args.path("people").asInt(1);
            Integer budget = args.has("budget") && !args.get("budget").isNull()
                    ? args.get("budget").asInt() : null;

            // 参数校验
            if (from.isBlank()) {
                return "{\"error\": \"缺少必填参数: from（出发地）\"}";
            }
            if (to.isBlank()) {
                return "{\"error\": \"缺少必填参数: to（目的地）\"}";
            }
            if (people <= 0) {
                return "{\"error\": \"参数 people 必须大于 0\"}";
            }

            log.info("TransportRecommendTool 执行 | from={} | to={} | people={} | budget={}",
                    from, to, people, budget);

            return transportService.recommend(new TransportRequest(from, to, people, budget));

        } catch (Exception e) {
            log.error("TransportRecommendTool 执行失败 | args={} | error={}",
                    argumentsJson, e.getMessage());
            return "{\"error\": \"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }
}