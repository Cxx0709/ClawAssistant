package com.youkeda.exercise.claw.agent.plan;

import com.youkeda.exercise.claw.agent.model.PlanState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * PlanStore 的内存实现（默认兜底，存储未启用时生效）。
 *
 * <p>存储启用时（storage.enabled=true）由 {@code SqlitePlanStore} 替换。
 */
@Component
@ConditionalOnMissingBean(PlanStore.class)
public class DefaultPlanStore implements PlanStore {

    private static final Logger log = LoggerFactory.getLogger(DefaultPlanStore.class);

    private volatile PlanState store;

    @Override
    public PlanState get() {
        return store;
    }

    @Override
    public void save(PlanState state) {
        this.store = state;
        log.debug("PlanState 已保存 | version={}", state != null ? state.getVersion() : "null");
    }

    @Override
    public void clear() {
        this.store = null;
        log.debug("PlanState 已清除");
    }
}
