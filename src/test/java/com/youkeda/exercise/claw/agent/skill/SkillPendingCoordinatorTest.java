package com.youkeda.exercise.claw.agent.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillPendingCoordinatorTest {

    private final SkillPendingCoordinator coordinator = new SkillPendingCoordinator(new ObjectMapper());

    @Test
    void clearsPendingAfterScoutToolExecutes() {
        SkillSession session = SkillSession.create("owner")
                .withActiveSkill("information-scout")
                .withPendingAction("START_INFORMATION_SCOUT", "query");

        SkillSession updated = coordinator.afterToolExecution(session, "information_scout");

        assertFalse(updated.hasPendingAction("START_INFORMATION_SCOUT"));
    }

    @Test
    void setsPendingAfterDidiRideEstimateCompletes() {
        SkillSession session = SkillSession.create("owner").withActiveSkill("transport");

        SkillSession updated = coordinator.afterToolExecution(
                session, "didi_ride",
                "{\"status\":\"estimate_completed\",\"origin_name\":\"南京邮电大学\"}");

        assertTrue(updated.hasPendingAction(SkillPendingCoordinator.RIDE_ESTIMATE_CONFIRM),
                "估价完成后应设置待确认标记，保证追问校区/车型期间保持 transport");
    }

    @Test
    void clearsPendingAfterDidiRideOrderCreated() {
        SkillSession session = SkillSession.create("owner")
                .withActiveSkill("transport")
                .withPendingAction(SkillPendingCoordinator.RIDE_ESTIMATE_CONFIRM, null);

        SkillSession updated = coordinator.afterToolExecution(
                session, "didi_ride",
                "{\"status\":\"order_created\",\"order_id\":\"123\"}");

        assertFalse(updated.hasPendingAction(SkillPendingCoordinator.RIDE_ESTIMATE_CONFIRM),
                "订单创建成功后应清除待确认标记");
    }

    @Test
    void clearsPendingAfterDidiRideOrderCancelled() {
        SkillSession session = SkillSession.create("owner")
                .withActiveSkill("transport")
                .withPendingAction(SkillPendingCoordinator.RIDE_ESTIMATE_CONFIRM, null);

        SkillSession updated = coordinator.afterToolExecution(
                session, "didi_ride",
                "{\"status\":\"cancelled\"}");

        assertFalse(updated.hasPendingAction(SkillPendingCoordinator.RIDE_ESTIMATE_CONFIRM),
                "订单取消后应清除待确认标记");
    }
}
