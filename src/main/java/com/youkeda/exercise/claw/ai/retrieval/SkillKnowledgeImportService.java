package com.youkeda.exercise.claw.ai.retrieval;

import com.youkeda.exercise.claw.agent.memory.longterm.EmbeddingClient;
import com.youkeda.exercise.claw.skill.SkillRegistry;
import com.youkeda.exercise.claw.skill.SkillsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class SkillKnowledgeImportService {

    private static final Logger log = LoggerFactory.getLogger(SkillKnowledgeImportService.class);
    private static final Pattern MARKDOWN_CONTENT = Pattern.compile("(?m)^#{1,6}\\s+.+");

    private final SkillKnowledgeStore store;
    private final EmbeddingClient embeddingClient;
    private final DocumentChunker chunker;
    private final SkillRegistry skillRegistry;
    private final SkillsProperties skillsProperties;

    @Value("${skill.knowledge.management.max-import-chars:200000}")
    private int maxImportChars = 200000;

    @Value("${skill.knowledge.management.max-chunks:500}")
    private int maxChunks = 500;

    @Value("${skill.knowledge.management.max-source-chars:512}")
    private int maxSourceChars = 512;

    @Value("${skill.knowledge.management.max-version-chars:64}")
    private int maxVersionChars = 64;

    public SkillKnowledgeImportService(SkillKnowledgeStore store,
                                       EmbeddingClient embeddingClient,
                                       DocumentChunker chunker) {
        this(store, embeddingClient, chunker, null, null);
    }

    public SkillKnowledgeImportService(SkillKnowledgeStore store,
                                       EmbeddingClient embeddingClient,
                                       DocumentChunker chunker,
                                       SkillRegistry skillRegistry) {
        this(store, embeddingClient, chunker, skillRegistry, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SkillKnowledgeImportService(SkillKnowledgeStore store,
                                       EmbeddingClient embeddingClient,
                                       DocumentChunker chunker,
                                       SkillRegistry skillRegistry,
                                       SkillsProperties skillsProperties) {
        this.store = store;
        this.embeddingClient = embeddingClient;
        this.chunker = chunker;
        this.skillRegistry = skillRegistry;
        this.skillsProperties = skillsProperties;
    }

    public KnowledgeImportResult importDocument(String skillName,
                                                String content,
                                                String source,
                                                String format,
                                                String version) {
        validateImport(skillName, content);
        String documentId = UUID.randomUUID().toString();
        String resolvedSource = source == null || source.isBlank() ? "manual" : source.trim();
        String resolvedVersion = version == null || version.isBlank() ? "1.0" : version.trim();
        validateMetadata(resolvedSource, resolvedVersion);
        boolean markdown = isMarkdown(format, resolvedSource, content);

        List<DocumentChunker.Chunk> chunks = markdown
                ? chunker.chunkMarkdown(content, documentId, resolvedSource)
                : chunker.chunkPlainText(content, documentId, resolvedSource);
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("content produced no knowledge chunks");
        }
        if (chunks.size() > maxChunks) {
            throw new IllegalArgumentException("document exceeds max chunks: " + maxChunks);
        }

        List<String> texts = chunks.stream().map(DocumentChunker.Chunk::content).toList();
        final List<float[]> vectors;
        try {
            vectors = embeddingClient.embedBatch(texts);
        } catch (Exception e) {
            throw new SkillKnowledgeImportException(
                    "Embedding failed; no knowledge was written", e);
        }
        if (vectors.size() != chunks.size()) {
            throw new SkillKnowledgeImportException(
                    "Embedding result count mismatch; no knowledge was written", null);
        }

        List<SkillKnowledgeVector> points = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunker.Chunk chunk = chunks.get(i);
            SkillKnowledgeChunk knowledgeChunk = new SkillKnowledgeChunk(
                    UUID.randomUUID().toString(), skillName.trim(), documentId,
                    chunk.chunkIndex(), chunk.content(), sha256(chunk.content()),
                    chunk.source(), chunk.heading(), null, resolvedVersion, false);
            points.add(new SkillKnowledgeVector(knowledgeChunk, vectors.get(i)));
        }

        try {
            store.upsertAll(points);
            long activated = store.setDocumentEnabled(skillName.trim(), documentId, true);
            if (activated != chunks.size()) {
                throw new SkillKnowledgeStoreException(
                        "Activation count mismatch: expected " + chunks.size() + ", actual " + activated,
                        null);
            }
            return new KnowledgeImportResult(
                    "IMPORTED", skillName.trim(), documentId, resolvedSource, resolvedVersion,
                    chunks.size(), chunks.size(), "");
        } catch (Exception writeFailure) {
            try {
                store.hardDeleteByDocument(skillName.trim(), documentId);
            } catch (Exception cleanupFailure) {
                writeFailure.addSuppressed(cleanupFailure);
                log.error("Knowledge import cleanup failed | skill={} | documentId={}",
                        skillName, documentId, cleanupFailure);
            }
            throw new SkillKnowledgeImportException(
                    "Knowledge store write failed; document was not activated", writeFailure);
        }
    }

    public long deleteDocument(String skillName, String documentId) {
        requireKnownSkill(skillName);
        return store.softDeleteByDocument(skillName.trim(), documentId);
    }

    public KnowledgeStoreStatus status(String skillName) {
        requireKnownSkill(skillName);
        String normalizedSkill = skillName.trim();
        KnowledgeStoreStatus backend = store.status(normalizedSkill);
        boolean globalEnabled = skillsProperties != null
                && skillsProperties.getKnowledge().isGlobalEnabled();
        boolean skillKnowledgeEnabled = skillRegistry != null
                && skillRegistry.find(normalizedSkill)
                .map(definition -> definition.knowledge() != null
                        && definition.knowledge().enabled())
                .orElse(false);
        String circuitState = embeddingClient.circuitStateName();
        return new KnowledgeStoreStatus(
                backend.available(), backend.collection(), backend.pointCount(), backend.message(),
                globalEnabled, skillKnowledgeEnabled, circuitState);
    }

    private boolean isMarkdown(String format, String source, String content) {
        String normalized = format == null ? "auto" : format.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "markdown", "md" -> true;
            case "text", "plain_text", "plain-text" -> false;
            case "", "auto" -> source.toLowerCase(Locale.ROOT).endsWith(".md")
                    || source.toLowerCase(Locale.ROOT).endsWith(".markdown")
                    || MARKDOWN_CONTENT.matcher(content).find();
            default -> throw new IllegalArgumentException("unsupported content format: " + format);
        };
    }

    private void validateMetadata(String source, String version) {
        if (source.length() > maxSourceChars) {
            throw new IllegalArgumentException("source exceeds max characters: " + maxSourceChars);
        }
        if (version.length() > maxVersionChars) {
            throw new IllegalArgumentException("version exceeds max characters: " + maxVersionChars);
        }
    }

    private void validateImport(String skillName, String content) {
        requireKnownSkill(skillName);
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content is required for import");
        }
        if (content.length() > maxImportChars) {
            throw new IllegalArgumentException(
                    "content exceeds max import characters: " + maxImportChars);
        }
    }

    private void requireKnownSkill(String skillName) {
        if (skillName == null || skillName.isBlank()) {
            throw new IllegalArgumentException("skillName is required");
        }
        if (skillRegistry != null && skillRegistry.find(skillName.trim()).isEmpty()) {
            throw new IllegalArgumentException("unknown or disabled skillName: " + skillName.trim());
        }
    }

    private String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to hash knowledge chunk", e);
        }
    }
}
