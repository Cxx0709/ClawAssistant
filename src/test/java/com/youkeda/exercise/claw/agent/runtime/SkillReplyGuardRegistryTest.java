package com.youkeda.exercise.claw.agent.runtime;

import com.youkeda.exercise.claw.agent.model.ResultStatus;
import com.youkeda.exercise.claw.agent.runtime.SkillReplyGuard.GuardContext;
import com.youkeda.exercise.claw.agent.runtime.SkillReplyGuard.GuardResult;
import com.youkeda.exercise.claw.agent.skill.SkillSession;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SkillReplyGuardRegistryTest {

    @Test
    void dispatchesToSkillSpecificGuard() {
        SkillReplyGuard guard = new SkillReplyGuard() {
            @Override public String getSkillName() { return "travel"; }
            @Override public GuardResult validate(GuardContext ctx) {
                return GuardResult.reject("travel 专属：先调 travel_collect");
            }
        };
        SkillReplyGuardRegistry registry = new SkillReplyGuardRegistry(java.util.List.of(guard));

        GuardResult result = registry.validate("travel", "我要去三亚",
                "已规划好", SkillSession.create("u"), Set.of(), Map.of());
        assertFalse(result.allowed());
        assertEquals("travel 专属：先调 travel_collect", result.correction());
    }

    @Test
    void allowsWhenNoGuardForSkill() {
        SkillReplyGuardRegistry registry = new SkillReplyGuardRegistry(java.util.List.of());
        GuardResult result = registry.validate("weather", "今天天气",
                "晴", SkillSession.create("u"), Set.of(), Map.of());
        assertTrue(result.allowed());
    }

    @Test
    void rejectsDuplicateSkillGuard() {
        SkillReplyGuard guard = new SkillReplyGuard() {
            @Override public String getSkillName() { return "travel"; }
            @Override public GuardResult validate(GuardContext ctx) { return GuardResult.allow(); }
        };
        assertThrows(IllegalStateException.class,
                () -> new SkillReplyGuardRegistry(java.util.List.of(guard, guard)));
    }

    @Test
    void guardsWithNullSkillRunForEverySkill() {
        SkillReplyGuard guard = new SkillReplyGuard() {
            @Override public String getSkillName() { return null; } // 全局
            @Override public GuardResult validate(GuardContext ctx) {
                return GuardResult.reject("全局 guard");
            }
        };
        SkillReplyGuardRegistry registry = new SkillReplyGuardRegistry(java.util.List.of(guard));
        GuardResult result = registry.validate("anySkill", "x", "y",
                SkillSession.create("u"), Set.of(), Map.of());
        assertFalse(result.allowed());
    }

    @Test
    void passesExactToolStatusesToGuard() {
        var captured = new java.util.concurrent.atomic.AtomicReference<Map<String, ResultStatus>>();
        SkillReplyGuard guard = new SkillReplyGuard() {
            @Override public String getSkillName() { return "test"; }
            @Override public GuardResult validate(GuardContext ctx) {
                captured.set(ctx.toolStatuses());
                return GuardResult.allow();
            }
        };
        SkillReplyGuardRegistry registry = new SkillReplyGuardRegistry(java.util.List.of(guard));
        // 用非 SUCCESS 值作为输入：若实现"盲全映射为 SUCCESS"，map 将不等，测试即可捕获
        Map<String, ResultStatus> input = Map.of("some_tool", ResultStatus.FAILED);
        registry.validate("test", "msg", "reply", SkillSession.create("u"),
                Set.of(), input);
        assertEquals(input, captured.get(),
                "toolStatuses 必须无修改地透传——禁止全映射为 SUCCESS");
    }

    @Test
    void allowsWhenToolStatusesNull() {
        // 含 guard 的 registry：null 输入必须真正走到 Map.copyOf 防御，而不是空 registry 短路
        var captured = new java.util.concurrent.atomic.AtomicReference<Map<String, ResultStatus>>();
        SkillReplyGuard guard = new SkillReplyGuard() {
            @Override public String getSkillName() { return "test"; }
            @Override public GuardResult validate(GuardContext ctx) {
                captured.set(ctx.toolStatuses());
                return GuardResult.allow();
            }
        };
        SkillReplyGuardRegistry registry = new SkillReplyGuardRegistry(java.util.List.of(guard));
        GuardResult result = registry.validate("test", "今天天气", "晴",
                SkillSession.create("u"), Set.of(), null);
        assertTrue(result.allowed());
        assertNotNull(captured.get());
        assertTrue(captured.get().isEmpty(),
                "toolStatuses=null 时必须被防御为空 map 传给 guard（而非 NPE）");
    }

    @Test
    void allowsWhenExecutedCallsNull() {
        var captured = new java.util.concurrent.atomic.AtomicReference<Set<String>>();
        SkillReplyGuard guard = new SkillReplyGuard() {
            @Override public String getSkillName() { return "test"; }
            @Override public GuardResult validate(GuardContext ctx) {
                captured.set(ctx.executedCalls());
                return GuardResult.allow();
            }
        };
        SkillReplyGuardRegistry registry = new SkillReplyGuardRegistry(java.util.List.of(guard));
        GuardResult result = registry.validate("test", "今天天气", "晴",
                SkillSession.create("u"), null, Map.of());
        assertTrue(result.allowed());
        assertNotNull(captured.get());
        assertTrue(captured.get().isEmpty(),
                "executedCalls=null 时必须被防御为空 set 传给 guard（而非 NPE）");
    }
}
