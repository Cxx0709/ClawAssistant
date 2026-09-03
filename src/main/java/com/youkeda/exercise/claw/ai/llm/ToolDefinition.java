package com.youkeda.exercise.claw.ai.llm;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 发给 LLM 的工具定义（对应 OpenAI Chat API {@code tools} 数组中每个元素）
 *
 * <p>示例（序列化后）：
 * <pre>{@code
 * {
 *   "type": "function",
 *   "function": {
 *     "name": "weather_query",
 *     "description": "查询指定城市的实时天气",
 *     "parameters": {
 *       "type": "object",
 *       "properties": {
 *         "city": { "type": "string", "description": "城市名称" }
 *       },
 *       "required": ["city"]
 *     }
 *   }
 * }
 * }</pre>
 *
 * @param name       函数名，LLM 靠它决定调哪个工具
 * @param description 函数描述，LLM 据此判断何时调用
 * @param parameters 参数 JSON Schema（{@code type: object, properties: {...}, required: [...]}）
 */
public record ToolDefinition(String name, String description, JsonNode parameters) {
}
