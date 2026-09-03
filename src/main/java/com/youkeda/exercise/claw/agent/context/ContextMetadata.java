package com.youkeda.exercise.claw.agent.context;

import java.util.List;

/**
 * Context 溯源元数据（ADR §5）。
 *
 * <p>turnId + 各来源占用 token。随组装结果透传并落结构化日志，
 * 不侵入 LLM 协议层（不引入 ContextItem 对象层）。
 */
public record ContextMetadata(String turnId, List<ContextSourceRef> sources) {

    public ContextMetadata {
        sources = sources == null ? List.of() : List.copyOf(sources);
    }

    /** 空元数据（无 turnId、无来源记录），供未启用时兜底。 */
    public static ContextMetadata empty() {
        return new ContextMetadata(null, List.of());
    }
}
