package com.youkeda.exercise.claw.feature.travel;

/** 旅游方案状态存储。 */
public interface TravelPlanStateStore {

    TravelPlanDraft get(String userId);

    void save(String userId, TravelPlanDraft draft);

    void clear(String userId);
}
