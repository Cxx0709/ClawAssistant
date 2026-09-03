package com.youkeda.exercise.claw.feature.travel;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/** 默认旅游方案状态存储（基于内存）。单用户场景忽略 userId。当没有其他 TravelPlanStateStore 时生效。 */
@Component
@ConditionalOnMissingBean(TravelPlanStateStore.class)
public class DefaultTravelPlanStateStore implements TravelPlanStateStore {

    private volatile TravelPlanDraft store;

    @Override
    public TravelPlanDraft get(String userId) {
        return store;
    }

    @Override
    public void save(String userId, TravelPlanDraft draft) {
        this.store = draft;
    }

    @Override
    public void clear(String userId) {
        this.store = null;
    }
}
