package com.youkeda.exercise.claw.web;

import com.youkeda.exercise.claw.agent.skill.SemanticTriggerPolicy;
import com.youkeda.exercise.claw.skill.SkillDefinition;
import com.youkeda.exercise.claw.skill.SkillRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/** Read-only diagnostics for skill routing; not a database administration endpoint. */
@RestController
@RequestMapping("/api/visualization")
public class VectorVisualizationController {
    private static final double MATCH_THRESHOLD = 0.65;
    private final SkillRegistry skillRegistry;
    private final SemanticTriggerPolicy semanticPolicy;

    public VectorVisualizationController(SkillRegistry skillRegistry, SemanticTriggerPolicy semanticPolicy) {
        this.skillRegistry = skillRegistry;
        this.semanticPolicy = semanticPolicy;
    }

    private List<SkillDefinition> skills() {
        return skillRegistry.getAll().stream().sorted(Comparator.comparing(SkillDefinition::name)).toList();
    }

    @GetMapping("/embeddings")
    public ResponseEntity<Map<String, Object>> getEmbeddings() {
        List<Map<String, Object>> points = new ArrayList<>();
        for (SkillDefinition skill : skills()) {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("skillName", skill.name());
            point.put("description", skill.description());
            point.put("priority", skill.priority());
            point.put("tags", skill.tags() == null ? Set.of() : skill.tags());
            point.put("exampleCount", semanticPolicy.getExampleCount(skill.name()));
            point.put("embeddingReady", semanticPolicy.hasSkillEmbedding(skill.name()));
            points.add(point);
        }
        return ResponseEntity.ok(Map.of("points", points, "totalSkills", points.size(),
                "matchThreshold", MATCH_THRESHOLD, "timestamp", System.currentTimeMillis()));
    }

    @PostMapping("/similarity")
    public ResponseEntity<Map<String, Object>> calculateSimilarity(@RequestBody String message) {
        if (message == null || message.isBlank() || message.length() > 4000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入 1–4000 字的测试消息");
        }
        Map<String, Double> scores;
        try {
            scores = semanticPolicy.similarities(message.trim());
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
        }
        List<Map<String, Object>> similarities = new ArrayList<>();
        for (SkillDefinition skill : skills()) {
            Double score = scores.get(skill.name());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("skillName", skill.name());
            row.put("confidence", score);
            row.put("matched", score != null && score >= MATCH_THRESHOLD);
            row.put("reason", score == null ? "技能向量未就绪" : "与技能代表向量的余弦相似度");
            similarities.add(row);
        }
        similarities.sort(Comparator.comparing((Map<String, Object> row) -> (Double) row.get("confidence"),
                Comparator.nullsLast(Comparator.reverseOrder())));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", message.trim());
        result.put("similarities", similarities);
        result.put("topMatch", similarities.stream().filter(row -> Boolean.TRUE.equals(row.get("matched")))
                .findFirst().orElse(null));
        result.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(result);
    }

    @PostMapping("/similarity/batch")
    public ResponseEntity<Map<String, Object>> calculateBatchSimilarity(@RequestBody List<String> messages) {
        if (messages == null || messages.isEmpty() || messages.size() > 20
                || messages.stream().anyMatch(message -> message == null || message.isBlank() || message.length() > 4000)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "每批支持 1–20 条消息，每条 1–4000 字");
        }
        return ResponseEntity.ok(Map.of("results", messages.stream()
                .map(message -> calculateSimilarity(message).getBody()).toList(), "totalMessages", messages.size(),
                "timestamp", System.currentTimeMillis()));
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        List<SkillDefinition> skills = skills();
        return ResponseEntity.ok(Map.of(
                "skillNames", skills.stream().map(SkillDefinition::name).toList(),
                "priorities", skills.stream().map(SkillDefinition::priority).toList(),
                "exampleCounts", skills.stream().map(skill -> semanticPolicy.getExampleCount(skill.name())).toList(),
                "totalSkills", skills.size(),
                "readyEmbeddings", skills.stream().filter(skill -> semanticPolicy.hasSkillEmbedding(skill.name())).count(),
                "avgPriority", skills.stream().mapToInt(SkillDefinition::priority).average().orElse(0),
                "timestamp", System.currentTimeMillis()));
    }
}
