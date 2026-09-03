package com.youkeda.exercise.claw.tool.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.exercise.claw.agent.runtime.ToolRegistry;
import com.youkeda.exercise.claw.feature.transport.didi.DidiRideService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 验证 {@link DidiRideTool} 的注册名与 skills.yml / transport.txt 中声明的工具名一致。
 *
 * <p>回归背景：工具注册名为 {@code didi_taxi}，但 transport 技能的 optionalTools
 * 声明的是 {@code didi_ride}。白名单交集为空，导致 didi_ride 从未暴露给 LLM，
 * 「帮我打车去X」只能得到「我没有叫车功能」的答复。
 */
class DidiRideToolTest {

    @Test
    void registeredNameMatchesSkillDeclaration() {
        DidiRideTool tool = new DidiRideTool(
                mock(DidiRideService.class), new ObjectMapper(), new ToolRegistry());
        assertEquals("didi_ride", tool.getName(),
                "工具注册名必须与 skills.yml transport.optionalTools 和 transport.txt 一致");
    }
}
