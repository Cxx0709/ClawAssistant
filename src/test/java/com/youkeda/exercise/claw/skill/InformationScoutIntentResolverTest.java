package com.youkeda.exercise.claw.skill;

import com.youkeda.exercise.claw.agent.skill.SkillSession;
import com.youkeda.exercise.claw.agent.skill.SkillPendingCoordinator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InformationScoutIntentResolverTest {

    private final InformationScoutIntentResolver resolver =
            new InformationScoutIntentResolver();

    @Test
    void resolvesBroadExplicitRequestAsProfileDiscovery() {
        SkillSession session = SkillSession.create("owner")
                .withActiveSkill("information-scout");

        InformationScoutIntent intent = resolver.resolve(
                "最近有什么值得关注的事情吗", session);

        assertEquals(InformationScoutIntent.Action.PROFILE_DISCOVERY, intent.action());
        assertEquals("", intent.query());
    }

    @Test
    void resolvesExplicitTopicRequestWithoutRequiringToolCall() {
        SkillSession session = SkillSession.create("owner")
                .withActiveSkill("information-scout");

        InformationScoutIntent intent = resolver.resolve(
                "跟踪 Claude Code 最近的更新", session);

        assertEquals(InformationScoutIntent.Action.TOPIC_SEARCH, intent.action());
        assertEquals("Claude Code 最近的更新", intent.query());
    }

    @Test
    void stripsSubscriptionVerbFromTopicQuery() {
        SkillSession session = SkillSession.create("owner")
                .withActiveSkill("information-scout");

        InformationScoutIntent intent = resolver.resolve("帮我订阅科技资讯", session);

        assertEquals(InformationScoutIntent.Action.TOPIC_SEARCH, intent.action());
        assertEquals("科技资讯", intent.query());
    }

    @Test
    void resolvesCollectNewsRequestAsTopicSearch() {
        SkillSession session = SkillSession.create("owner")
                .withActiveSkill("information-scout");

        InformationScoutIntent intent = resolver.resolve(
                "帮我搜集一些关于AI的新闻", session);

        assertEquals(InformationScoutIntent.Action.TOPIC_SEARCH, intent.action());
        assertTrue(intent.query().contains("AI"));
    }

    @Test
    void asksForTopicWhenRequestHasNoTopicAndIsNotProfileDiscovery() {
        SkillSession session = SkillSession.create("owner")
                .withActiveSkill("information-scout");

        InformationScoutIntent intent = resolver.resolve("帮我查一下", session);

        assertEquals(InformationScoutIntent.Action.NEED_CLARIFICATION, intent.action());
        assertEquals("你想重点查找哪个主题？", intent.clarification());
    }

    @Test
    void doesNotActOnInterestStatementWithoutExplicitRequest() {
        SkillSession session = SkillSession.create("owner")
                .withActiveSkill("information-scout");

        InformationScoutIntent intent = resolver.resolve("我最近对大模型很感兴趣", session);

        assertEquals(InformationScoutIntent.Action.NO_ACTION, intent.action());
    }

    @Test
    void doesNotTreatSkillNameAloneAsExecutionAuthorization() {
        InformationScoutIntent intent = resolver.resolve(
                "信息猎手", SkillSession.create("owner"));

        assertEquals(InformationScoutIntent.Action.NO_ACTION, intent.action());
    }

    @Test
    void acceptsShortTopicAsAnswerToPendingClarification() {
        SkillSession session = SkillSession.create("owner")
                .withActiveSkill("information-scout")
                .withPendingAction(SkillPendingCoordinator.START_INFORMATION_SCOUT, "query");

        InformationScoutIntent intent = resolver.resolve("大模型推理优化", session);

        assertEquals(InformationScoutIntent.Action.TOPIC_SEARCH, intent.action());
        assertEquals("大模型推理优化", intent.query());
    }

    @Test
    void cancellationDoesNotBecomePendingTopic() {
        SkillSession session = SkillSession.create("owner")
                .withActiveSkill("information-scout")
                .withPendingAction(SkillPendingCoordinator.START_INFORMATION_SCOUT, "query");

        InformationScoutIntent intent = resolver.resolve("算了，不查了", session);

        assertEquals(InformationScoutIntent.Action.CANCEL, intent.action());
    }

    @Test
    void confirmationWhilePendingDoesNotBecomeASearchTopic() {
        SkillSession session = SkillSession.create("owner")
                .withActiveSkill("information-scout")
                .withPendingAction(SkillPendingCoordinator.START_INFORMATION_SCOUT, "query");

        InformationScoutIntent intent = resolver.resolve("好的", session);

        assertEquals(InformationScoutIntent.Action.CANCEL, intent.action());
    }

    @Test
    void nonTopicRepliesWhilePendingNeverStartNetworkSearch() {
        SkillSession session = SkillSession.create("owner")
                .withActiveSkill("information-scout")
                .withPendingAction(SkillPendingCoordinator.START_INFORMATION_SCOUT, "query");

        for (String reply : List.of("谢谢", "然后呢", "好的呀", "不知道呢", "随便")) {
            InformationScoutIntent intent = resolver.resolve(reply, session);
            assertEquals(InformationScoutIntent.Action.CANCEL, intent.action(), reply);
        }
    }

    @Test
    void genericActivityWordsWithoutTopicNeedClarification() {
        for (String request : List.of("帮我关注最近动态", "关注动态")) {
            InformationScoutIntent intent = resolver.resolve(
                    request, SkillSession.create("owner"));
            assertEquals(InformationScoutIntent.Action.NEED_CLARIFICATION,
                    intent.action(), request);
        }
    }

    @Test
    void explicitSkillCommandUsesProfileDiscoveryMode() {
        InformationScoutIntent intent = resolver.resolve(
                "使用信息猎手", SkillSession.create("owner"));

        assertEquals(InformationScoutIntent.Action.PROFILE_DISCOVERY, intent.action());
    }
}
