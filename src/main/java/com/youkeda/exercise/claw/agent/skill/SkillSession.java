package com.youkeda.exercise.claw.agent.skill;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public record SkillSession(
        String userId,
        String activeSkill,
        String previousSkill,
        Instant activatedAt,
        Instant lastActivityAt,
        int inactivityCount,
        Map<String, String> context
) {

    private static final String PENDING_ACTION = "pendingAction";
    private static final String PENDING_SLOT = "pendingSlot";

    public SkillSession(String userId, String activeSkill, String previousSkill,
                        Instant activatedAt, Instant lastActivityAt, int inactivityCount) {
        this(userId, activeSkill, previousSkill, activatedAt, lastActivityAt,
                inactivityCount, Map.of());
    }

    public SkillSession {
        context = context == null ? Map.of() : Map.copyOf(context);
    }

    public static SkillSession create(String userId) {
        Instant now = Instant.now();
        return new SkillSession(userId, "common", null, now, now, 0, Map.of());
    }

    public SkillSession withActiveSkill(String newSkill) {
        return new SkillSession(
                this.userId,
                newSkill,
                newSkill.equals(this.activeSkill) ? this.previousSkill : this.activeSkill,
                this.activatedAt,
                Instant.now(),
                0,
                newSkill.equals(this.activeSkill) ? this.context : Map.of()
        );
    }

    public SkillSession withIncrementInactivity() {
        return new SkillSession(
                this.userId,
                this.activeSkill,
                this.previousSkill,
                this.activatedAt,
                this.lastActivityAt,
                this.inactivityCount + 1,
                this.context
        );
    }

    public SkillSession withResetInactivity() {
        return new SkillSession(
                this.userId,
                this.activeSkill,
                this.previousSkill,
                this.activatedAt,
                Instant.now(),
                0,
                this.context
        );
    }

    public SkillSession withPendingAction(String action, String slot) {
        Map<String, String> updated = new HashMap<>(context);
        updated.put(PENDING_ACTION, action);
        if (slot == null || slot.isBlank()) {
            updated.remove(PENDING_SLOT);
        } else {
            updated.put(PENDING_SLOT, slot);
        }
        return new SkillSession(userId, activeSkill, previousSkill, activatedAt,
                Instant.now(), inactivityCount, updated);
    }

    public boolean hasPendingAction(String action) {
        return action != null && action.equals(context.get(PENDING_ACTION));
    }

    public String pendingSlot() {
        return context.get(PENDING_SLOT);
    }

    public SkillSession clearPendingAction() {
        if (!context.containsKey(PENDING_ACTION) && !context.containsKey(PENDING_SLOT)) {
            return this;
        }
        Map<String, String> updated = new HashMap<>(context);
        updated.remove(PENDING_ACTION);
        updated.remove(PENDING_SLOT);
        return new SkillSession(userId, activeSkill, previousSkill, activatedAt,
                Instant.now(), inactivityCount, updated);
    }
}
