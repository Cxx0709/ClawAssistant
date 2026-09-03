package com.youkeda.exercise.claw.skill;

public record InformationScoutIntent(
        Action action,
        String query,
        String clarification
) {
    public enum Action {
        PROFILE_DISCOVERY,
        TOPIC_SEARCH,
        NEED_CLARIFICATION,
        CANCEL,
        NO_ACTION
    }

    public static InformationScoutIntent profileDiscovery() {
        return new InformationScoutIntent(Action.PROFILE_DISCOVERY, "", null);
    }

    public static InformationScoutIntent topicSearch(String query) {
        return new InformationScoutIntent(Action.TOPIC_SEARCH, query, null);
    }

    public static InformationScoutIntent needClarification(String question) {
        return new InformationScoutIntent(Action.NEED_CLARIFICATION, "", question);
    }

    public static InformationScoutIntent noAction() {
        return new InformationScoutIntent(Action.NO_ACTION, "", null);
    }

    public static InformationScoutIntent cancel() {
        return new InformationScoutIntent(Action.CANCEL, "", null);
    }
}
