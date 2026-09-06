package com.youkeda.exercise.claw.agent.memory.longterm;

import java.util.Set;

/** Controls whether a conversation may trigger automatic long-term memory extraction. */
public enum MemoryPolicy {
    AUTO_EXTRACT,
    SKIP_AUTO,
    MANUAL_ONLY;

    public boolean allowsAutoExtract() {
        return this == AUTO_EXTRACT;
    }

    private static final Set<String> ONE_SHOT_TOOLS = Set.of(
            "weather_query", "map_search_place", "map_route_planning", "map_distance_calculate",
            "web_search", "file_generate", "file_read", "file_search", "place_image_search",
            "transport_recommend");

    public static boolean hasOneShotTool(Set<String> toolNames) {
        return toolNames != null && toolNames.stream().anyMatch(ONE_SHOT_TOOLS::contains);
    }

    public static MemoryPolicy forSkill(String skillName) {
        if (skillName == null || skillName.isBlank()) return AUTO_EXTRACT;
        return switch (skillName.toLowerCase()) {
            case "travel", "weather", "transport" -> SKIP_AUTO;
            default -> AUTO_EXTRACT;
        };
    }
}
