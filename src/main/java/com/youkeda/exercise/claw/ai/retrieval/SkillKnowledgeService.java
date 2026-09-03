package com.youkeda.exercise.claw.ai.retrieval;

import com.youkeda.exercise.claw.agent.memory.longterm.EmbeddingClient;
import com.youkeda.exercise.claw.skill.SkillDefinition;
import com.youkeda.exercise.claw.skill.SkillRegistry;
import com.youkeda.exercise.claw.skill.SkillsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class SkillKnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(SkillKnowledgeService.class);
    private static final int MAX_CHUNKS_PER_DOCUMENT = 2;

    private final SkillKnowledgeStore knowledgeStore;
    private final EmbeddingClient embeddingClient;
    private final SkillRegistry skillRegistry;
    private final SkillsProperties skillsProperties;
    private final KnowledgePromptFormatter promptFormatter;

    @Value("${skill.knowledge.recall-top-k:5}")
    private int topK;

    @Value("${skill.knowledge.min-score:0.50}")
    private float minScore;

    @Value("${skill.knowledge.recall-candidate-multiplier:3}")
    private int candidateMultiplier;

    @Value("${skill.knowledge.max-context-chars:12000}")
    private int maxContextChars;

    public SkillKnowledgeService(SkillKnowledgeStore knowledgeStore,
                                 EmbeddingClient embeddingClient,
                                 SkillRegistry skillRegistry,
                                 SkillsProperties skillsProperties,
                                 KnowledgePromptFormatter promptFormatter) {
        this.knowledgeStore = knowledgeStore;
        this.embeddingClient = embeddingClient;
        this.skillRegistry = skillRegistry;
        this.skillsProperties = skillsProperties;
        this.promptFormatter = promptFormatter;
    }

    public String recall(String userMessage, String primarySkillName) {
        long startedAt = System.nanoTime();
        if (!skillsProperties.getKnowledge().isGlobalEnabled()) {
            log.debug("Skill knowledge recall skipped | skill={} | outcome=disabled_global", primarySkillName);
            return "";
        }
        if (userMessage == null || userMessage.isBlank() || primarySkillName == null
                || primarySkillName.isBlank()) {
            return "";
        }

        SkillDefinition skillDef = skillRegistry.find(primarySkillName).orElse(null);
        if (skillDef == null || skillDef.knowledge() == null || !skillDef.knowledge().enabled()) {
            log.debug("Skill knowledge recall skipped | skill={} | outcome=disabled_skill", primarySkillName);
            return "";
        }

        SkillKnowledgeConfig config = skillDef.knowledge();
        int effectiveTopK = config.topK() > 0 ? config.topK() : Math.max(1, topK);
        float effectiveMinScore = config.minScore() > 0 ? config.minScore() : minScore;
        int budget = config.maxContextChars() > 0 ? config.maxContextChars() : maxContextChars;
        int candidateLimit = Math.min(100,
                effectiveTopK * Math.max(1, candidateMultiplier));

        try {
            float[] queryVector = embeddingClient.embed(userMessage);
            List<SkillKnowledgeSearchResult> candidates = knowledgeStore.search(
                    queryVector, Set.of(primarySkillName), candidateLimit, effectiveMinScore);
            if (candidates == null || candidates.isEmpty()) {
                logRecall(primarySkillName, "no_hit", 0, 0, 0, startedAt);
                return "";
            }

            List<SkillKnowledgeSearchResult> selected = selectCandidates(candidates, candidateLimit);
            String prompt = promptFormatter.format(selected, budget, effectiveTopK);
            String outcome = prompt.isEmpty() ? "budget_empty" : "success";
            logRecall(primarySkillName, outcome, candidates.size(), selected.size(),
                    prompt.length(), startedAt);
            return prompt;
        } catch (Exception e) {
            log.warn("Skill knowledge recall failed; continuing without knowledge | skill={} | outcome=error",
                    primarySkillName, e);
            return "";
        }
    }

    private List<SkillKnowledgeSearchResult> selectCandidates(
            List<SkillKnowledgeSearchResult> candidates, int limit) {
        List<SkillKnowledgeSearchResult> sorted = candidates.stream()
                .filter(result -> result != null && result.content() != null
                        && !result.content().isBlank())
                .sorted(Comparator.comparingDouble(SkillKnowledgeSearchResult::score).reversed())
                .toList();

        Set<String> seenContent = new HashSet<>();
        Map<String, Integer> chunksPerDocument = new HashMap<>();
        List<SkillKnowledgeSearchResult> selected = new ArrayList<>();
        for (SkillKnowledgeSearchResult result : sorted) {
            String dedupKey = result.contentHash() == null || result.contentHash().isBlank()
                    ? result.chunkId() : result.contentHash();
            if (!seenContent.add(dedupKey)) continue;

            String documentKey = result.documentId() == null || result.documentId().isBlank()
                    ? result.chunkId() : result.documentId();
            int documentCount = chunksPerDocument.getOrDefault(documentKey, 0);
            if (documentCount >= MAX_CHUNKS_PER_DOCUMENT) continue;

            selected.add(result);
            chunksPerDocument.put(documentKey, documentCount + 1);
            if (selected.size() >= limit) break;
        }
        return selected;
    }

    private void logRecall(String skill, String outcome, int candidates,
                           int selected, int chars, long startedAt) {
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
        log.info("Skill knowledge recall | skill={} | outcome={} | candidates={} | selected={} | chars={} | elapsedMs={}",
                skill, outcome, candidates, selected, chars, elapsedMs);
    }
}
