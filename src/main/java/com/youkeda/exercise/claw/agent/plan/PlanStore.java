package com.youkeda.exercise.claw.agent.plan;

import com.youkeda.exercise.claw.agent.model.PlanState;

/**
 * PlanState 的读写接口。
 */
public interface PlanStore {

    /** 获取当前 PlanState，没有则返回 null */
    PlanState get();

    /** 保存（创建或更新）PlanState */
    void save(PlanState state);

    /** 清理 PlanState */
    void clear();
}
