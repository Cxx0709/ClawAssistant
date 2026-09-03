package com.youkeda.exercise.claw.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具基类：收敛注册样板 + schema 声明式构造。
 *
 * <p>所有工具 {@code extends AbstractTool} 即可：
 * <ul>
 *   <li>注册：{@code @PostConstruct} 自动向 {@link ToolRegistry} 自注册，
 *       子类无需再写 init 方法；需跳过注册的（如 {@code TencentMapTool} 仅为满足架构约束）
 *       覆盖 {@link #shouldSelfRegister()} 返回 false。</li>
 *   <li>schema：{@link #schema()} 返回声明式构造器，替代手写 {@code ObjectNode} 样板。</li>
 * </ul>
 *
 * <p>execute 契约统一走 {@link Tool#execute(String, ToolExecutionContext)}（唯一抽象方法），
 * 单参数版本由接口 default 转发到 {@link ToolExecutionContext#EMPTY}。
 */
public abstract class AbstractTool implements Tool {

    protected final ToolRegistry registry;
    protected final ObjectMapper objectMapper;

    protected AbstractTool(ToolRegistry registry, ObjectMapper objectMapper) {
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    /**
     * 是否允许自注册到 {@link ToolRegistry}。默认 true；
     * 仅为满足架构约束、不作为 LLM 工具的类应覆盖返回 false。
     */
    protected boolean shouldSelfRegister() {
        return true;
    }

    /**
     * 初始化钩子。子类可覆盖以在自注册之外执行额外初始化
     * （如 {@code TencentMapTool} 在 init 中注册内部聚合工具）。
     */
    protected void onInit() {
    }

    @PostConstruct
    public final void init() {
        if (shouldSelfRegister()) {
            registry.register(this);
        }
        onInit();
    }

    /**
     * 声明式 schema 构造器入口。
     * <p>用法：
     * <pre>{@code
     * return schema()
     *     .string("city", "城市名称", true)
     *     .integer("limit", "返回条数", false)
     *     .array("plans", "候选方案", true)
     *         .string("plan_id", "方案标识", true)
     *         .end()
     *     .build();
     * }</pre>
     */
    protected SchemaBuilder schema() {
        return new SchemaBuilder(objectMapper);
    }

    /**
     * 声明式 JSON Schema 构造器。
     *
     * <p>支持嵌套（{@link #object(String, String, boolean)} / {@link #array(String, String, boolean)}），
     * 进入子级后用 {@link #end()} 回到父级。构建完成调用 {@link #build()} 返回根节点
     * （递归终结所有层级的 required 数组）。
     */
    public static final class SchemaBuilder {

        private final ObjectMapper om;
        private final ObjectNode node;
        private final ObjectNode properties;
        private final SchemaBuilder parent;
        private final List<String> required = new ArrayList<>();
        private final List<SchemaBuilder> children = new ArrayList<>();

        SchemaBuilder(ObjectMapper om) {
            this.om = om;
            this.node = om.createObjectNode();
            node.put("type", "object");
            this.properties = node.putObject("properties");
            this.parent = null;
        }

        private SchemaBuilder(ObjectMapper om, ObjectNode node, SchemaBuilder parent) {
            this.om = om;
            this.node = node;
            this.properties = node.putObject("properties");
            this.parent = parent;
        }

        public SchemaBuilder string(String name, String description, boolean required) {
            return put(name, "string", description, required);
        }

        public SchemaBuilder string(String name, String description) {
            return string(name, description, false);
        }

        public SchemaBuilder integer(String name, String description, boolean required) {
            return put(name, "integer", description, required);
        }

        public SchemaBuilder integer(String name, String description) {
            return integer(name, description, false);
        }

        public SchemaBuilder number(String name, String description, boolean required) {
            return put(name, "number", description, required);
        }

        public SchemaBuilder number(String name, String description) {
            return number(name, description, false);
        }

        public SchemaBuilder bool(String name, String description, boolean required) {
            return put(name, "boolean", description, required);
        }

        public SchemaBuilder bool(String name, String description) {
            return bool(name, description, false);
        }

        /**
         * 直接放入预构建的属性节点（如需 enum/pattern 等额外字段）。
         */
        public SchemaBuilder raw(String name, ObjectNode propertyNode, boolean required) {
            properties.set(name, propertyNode);
            if (required) {
                this.required.add(name);
            }
            return this;
        }

        private SchemaBuilder put(String name, String type, String description, boolean required) {
            ObjectNode property = properties.putObject(name);
            property.put("type", type);
            property.put("description", description);
            if (required) {
                this.required.add(name);
            }
            return this;
        }

        /**
         * 嵌套对象属性。进入子级构建其 properties，用 {@link #end()} 回到父级。
         */
        public SchemaBuilder object(String name, String description, boolean required) {
            ObjectNode child = properties.putObject(name);
            child.put("type", "object");
            child.put("description", description);
            if (required) {
                this.required.add(name);
            }
            SchemaBuilder childBuilder = new SchemaBuilder(om, child, this);
            children.add(childBuilder);
            return childBuilder;
        }

        /**
         * 数组属性（元素为对象）。进入 items 的 properties 子级，用 {@link #end()} 回到父级。
         */
        public SchemaBuilder array(String name, String description, boolean required) {
            ObjectNode child = properties.putObject(name);
            child.put("type", "array");
            child.put("description", description);
            if (required) {
                this.required.add(name);
            }
            ObjectNode items = child.putObject("items");
            items.put("type", "object");
            SchemaBuilder childBuilder = new SchemaBuilder(om, items, this);
            children.add(childBuilder);
            return childBuilder;
        }

        /**
         * 数组属性（元素为标量），如 {@code {type: array, items: {type: string}}}。
         */
        public SchemaBuilder arrayOfScalar(String name, String description, String itemType, boolean required) {
            ObjectNode child = properties.putObject(name);
            child.put("type", "array");
            child.put("description", description);
            if (required) {
                this.required.add(name);
            }
            child.putObject("items").put("type", itemType);
            return this;
        }

        /**
         * 回到父级（root 上调用为 no-op）。
         */
        public SchemaBuilder end() {
            return parent != null ? parent : this;
        }

        /**
         * 构建 schema 根节点，递归终结所有层级的 required 数组。
         */
        public ObjectNode build() {
            return root().finish().node;
        }

        private SchemaBuilder finish() {
            if (!required.isEmpty()) {
                ArrayNode requiredNode = node.putArray("required");
                required.forEach(requiredNode::add);
            }
            for (SchemaBuilder child : children) {
                child.finish();
            }
            return this;
        }

        private SchemaBuilder root() {
            return parent == null ? this : parent.root();
        }
    }
}
