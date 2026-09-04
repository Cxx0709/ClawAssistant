package com.youkeda.exercise.claw.agent;

import com.youkeda.exercise.claw.agent.skill.SkillRouter;
import com.youkeda.exercise.claw.agent.skill.SkillRoutingResult;
import com.youkeda.exercise.claw.agent.skill.SkillSession;
import com.youkeda.exercise.claw.agent.skill.SkillSessionStore;

/**
 * Skill 会话更新器。
 *
 * <p>从 {@code ReActAgentExecutor} 拆出：根据 {@code SkillRouter} 的路由结果更新
 * {@code SkillSession}。非 Spring bean，由 {@code ReActAgentExecutor} 构造时用已有依赖创建。
 */
public class SkillSessionUpdater {

    private final SkillRouter skillRouter;
    private final SkillSessionStore skillSessionStore;

    public SkillSessionUpdater(SkillRouter skillRouter, SkillSessionStore skillSessionStore) {
        this.skillRouter = skillRouter;
        this.skillSessionStore = skillSessionStore;
    }

    /**
     * 根据 SkillRouter 的 routing 结果更新 SkillSession。
     * <ul>
     *   <li>ACTIVATE/SWITCH → 切换 activeSkill</li>
     *   <li>CONTINUE → 高置信度重置不活跃计数，低置信度递增</li>
     *   <li>DEACTIVATE → 创建新 session（回退 common）</li>
     *   <li>NONE → 非 common 时递增不活跃计数</li>
     * </ul>
     */
    public SkillSession update(String userId, SkillRoutingResult routing) {
        java.util.Optional<SkillSession> existing = skillSessionStore.find(userId);
        SkillSession session = existing.orElseGet(() -> SkillSession.create(userId));

        switch (routing.action()) {
            case ACTIVATE, SWITCH, MULTI_ACTIVATE -> session = session.withActiveSkill(routing.primarySkill());
            case CONTINUE -> {
                if (routing.confidence() >= 0.3) {
                    session = session.withResetInactivity();
                } else {
                    session = session.withIncrementInactivity();
                }
            }
            case DEACTIVATE -> { session = SkillSession.create(userId); }
            case NONE -> {
                if (!"common".equals(session.activeSkill())) {
                    session = session.withIncrementInactivity();
                }
            }
        }
        skillSessionStore.save(userId, session);
        return session;
    }
}
