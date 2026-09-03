package com.youkeda.exercise.claw.tool.map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.youkeda.exercise.claw.agent.runtime.AbstractTool;
import com.youkeda.exercise.claw.agent.runtime.ToolExecutionContext;
import com.youkeda.exercise.claw.agent.runtime.ToolRegistry;
import com.youkeda.exercise.claw.feature.map.PlaceImageService;
import com.youkeda.exercise.claw.artifact.ArtifactKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 地点图片搜索 LLM Function
 *
 * <p>将地点图片搜索能力以 LLM Function Calling 的方式暴露给 ReActAgentExecutor。
 * 注册函数名 {@code place_image_search}。
 *
 * <p><b>图片发送采用 stash-consume 模式：</b>
 * <ol>
 *   <li>{@link #execute(String, ToolExecutionContext)} 获取图片 bytes 并暂存到 {@link #pendingPlaceImages}</li>
 *   <li>返回简化的 JSON 给 LLM（不含图片 URL）</li>
 *   <li>ChatHandler 通过 {@link #consumePendingPlaceImages()} 消费图片直接发送</li>
 * </ol>
 *
 * <p>LLM 只收到地点名称和描述，用自己的话生成文字介绍，不接触图片数据。
 */
@Component
public class PlaceImageTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(PlaceImageTool.class);

    private final PlaceImageService placeImageService;

    public PlaceImageTool(PlaceImageService placeImageService,
                              ToolRegistry functionRegistry,
                              ObjectMapper objectMapper) {
        super(functionRegistry, objectMapper);
        this.placeImageService = placeImageService;
    }

    // ==================== Tool ====================

    @Override
    public String getName() {
        return "place_image_search";
    }

    @Override
    public String getDescription() {
        return "根据地点关键词和城市搜索地点相关图片。" +
                "当用户询问某地有哪些景点、团建场所、旅游目的地并希望看到图片时调用。" +
                "调用后你会收到地点名称和描述，请用自然语言向用户介绍该地点，" +
                "不需要在回复中嵌入任何图片URL或JSON格式。图片会自动发送给用户。";
    }

    @Override
    public JsonNode getParameters() {
        return schema()
                .string("keyword", "地点关键词，如：西湖、灵隐寺、团建基地、户外拓展", true)
                .string("city", "城市名称，如：杭州、上海、北京", false)
                .build();
    }

    @Override
    public String execute(String argumentsJson, ToolExecutionContext context) {
        try {
            JsonNode args = objectMapper.readTree(argumentsJson);
            String keyword = args.path("keyword").asText("");
            String city = args.path("city").asText("");

            if (keyword.isBlank()) {
                return errorResult("缺少必填参数: keyword");
            }

            log.info("PlaceImageTool 执行 | keyword={} | city={}", keyword, city);

            // 获取图片字节
            List<byte[]> imageBytes = placeImageService.searchImageBytes(keyword, city);

            int emitted = 0;
            if (imageBytes != null && !imageBytes.isEmpty()) {
                for (byte[] bytes : imageBytes) {
                    if (bytes != null && bytes.length > 0) {
                        context.artifacts().emit(ArtifactKind.IMAGE, bytes, "image/jpeg",
                                keyword + "-" + (emitted + 1) + ".jpg", keyword);
                        emitted++;
                    }
                }
            }

            // 返回给 LLM 的简洁信息（不含图片 URL、不含 _hint）
            ObjectNode result = objectMapper.createObjectNode();
            result.put("name", keyword);
            result.put("city", city != null ? city : "");
            result.put("description", generateDescription(keyword, city));
            result.put("imageCount", emitted);
            result.put("imagesSent", emitted > 0);

            log.info("PlaceImageTool 完成 | keyword={} | city={} | imagesEmitted={}",
                    keyword, city, emitted);
            return result.toString();

        } catch (Exception e) {
            log.error("PlaceImageTool 执行失败 | args={} | error={}",
                    argumentsJson, e.getMessage());
            return errorResult("地点图片搜索失败：" + e.getMessage());
        }
    }

    /**
     * 生成地点简介
     */
    private String generateDescription(String keyword, String city) {
        String location = (city != null && !city.isBlank()) ? city : "";
        return location + "的" + keyword + "是一处值得探索的地点。";
    }

    private String errorResult(String message) {
        try {
            ObjectNode result = objectMapper.createObjectNode();
            result.put("status", "ERROR");
            result.put("error", message);
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            return "{\"status\":\"ERROR\",\"error\":\"" + message + "\"}";
        }
    }
}
