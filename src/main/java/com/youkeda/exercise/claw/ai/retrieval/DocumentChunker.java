package com.youkeda.exercise.claw.ai.retrieval;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class DocumentChunker {

    private static final int DEFAULT_CHUNK_SIZE = 2000;
    private static final int DEFAULT_OVERLAP = 250;
    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*$");

    public List<Chunk> chunkMarkdown(String text, String documentId, String source) {
        if (text == null || text.isBlank()) return List.of();

        List<Chunk> chunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();
        String[] headingLevels = new String[6];
        String currentHeading = "";
        int chunkIndex = 0;

        for (String line : text.split("\\R", -1)) {
            Matcher headingMatcher = MARKDOWN_HEADING.matcher(line);
            if (headingMatcher.matches()) {
                String overlap = "";
                if (!currentChunk.toString().isBlank()) {
                    String completed = currentChunk.toString().strip();
                    chunks.add(new Chunk(documentId, chunkIndex++,
                            completed, source, currentHeading));
                    overlap = retainOverlap(completed);
                }
                int level = headingMatcher.group(1).length();
                headingLevels[level - 1] = headingMatcher.group(2).trim();
                Arrays.fill(headingLevels, level, headingLevels.length, null);
                currentHeading = buildHeadingPath(headingLevels);
                currentChunk = new StringBuilder(overlap);
                if (!overlap.isEmpty()) currentChunk.append('\n');
                currentChunk.append(line).append('\n');
                continue;
            }

            int incomingLength = line.length() + 1;
            if (currentChunk.length() > 0
                    && currentChunk.length() + incomingLength > DEFAULT_CHUNK_SIZE) {
                String completed = currentChunk.toString().stripTrailing();
                chunks.add(new Chunk(documentId, chunkIndex++, completed, source, currentHeading));
                currentChunk = new StringBuilder(retainOverlap(completed));
                if (currentChunk.length() > 0) currentChunk.append('\n');
            }
            currentChunk.append(line).append('\n');

            while (currentChunk.length() > DEFAULT_CHUNK_SIZE) {
                String completed = currentChunk.substring(0, DEFAULT_CHUNK_SIZE).stripTrailing();
                chunks.add(new Chunk(documentId, chunkIndex++, completed, source, currentHeading));
                String overlap = retainOverlap(completed);
                String remaining = currentChunk.substring(DEFAULT_CHUNK_SIZE);
                currentChunk = new StringBuilder(overlap);
                if (!overlap.isEmpty() && !remaining.isEmpty()) currentChunk.append('\n');
                currentChunk.append(remaining);
            }
        }

        if (!currentChunk.toString().isBlank()) {
            chunks.add(new Chunk(documentId, chunkIndex,
                    currentChunk.toString().strip(), source, currentHeading));
        }
        return chunks;
    }

    public List<Chunk> chunkPlainText(String text, String documentId, String source) {
        if (text == null || text.isBlank()) return List.of();

        List<Chunk> chunks = new ArrayList<>();
        String[] paragraphs = text.split("\\R\\s*\\R+");
        StringBuilder currentChunk = new StringBuilder();
        int chunkIndex = 0;

        for (String paragraph : paragraphs) {
            String trimmed = paragraph.trim();
            if (trimmed.isEmpty()) continue;

            int separatorLength = currentChunk.length() == 0 ? 0 : 2;
            if (currentChunk.length() > 0
                    && currentChunk.length() + separatorLength + trimmed.length() > DEFAULT_CHUNK_SIZE) {
                String completed = currentChunk.toString().strip();
                chunks.add(new Chunk(documentId, chunkIndex++, completed, source, ""));
                currentChunk = new StringBuilder(retainOverlap(completed));
            }
            if (currentChunk.length() > 0) currentChunk.append("\n\n");
            currentChunk.append(trimmed);

            while (currentChunk.length() > DEFAULT_CHUNK_SIZE) {
                String completed = currentChunk.substring(0, DEFAULT_CHUNK_SIZE).stripTrailing();
                chunks.add(new Chunk(documentId, chunkIndex++, completed, source, ""));
                String overlap = retainOverlap(completed);
                String remaining = currentChunk.substring(DEFAULT_CHUNK_SIZE);
                currentChunk = new StringBuilder(overlap).append(remaining);
            }
        }

        if (!currentChunk.toString().isBlank()) {
            chunks.add(new Chunk(documentId, chunkIndex,
                    currentChunk.toString().strip(), source, ""));
        }
        return chunks;
    }

    private String retainOverlap(String completedChunk) {
        String normalized = completedChunk == null ? "" : completedChunk.stripTrailing();
        if (normalized.isEmpty()) return "";

        int overlapLength = Math.min(DEFAULT_OVERLAP, normalized.length() - 1);
        if (overlapLength <= 0) return "";
        int start = normalized.length() - overlapLength;
        int newline = normalized.indexOf('\n', start);
        if (newline >= start && newline + 1 < normalized.length()) {
            start = newline + 1;
            while (start < normalized.length() && Character.isWhitespace(normalized.charAt(start))) {
                start++;
            }
        }
        return normalized.substring(start);
    }

    private String buildHeadingPath(String[] headingLevels) {
        return Arrays.stream(headingLevels)
                .filter(value -> value != null && !value.isBlank())
                .reduce((left, right) -> left + " > " + right)
                .orElse("");
    }

    public record Chunk(String documentId, int chunkIndex, String content, String source, String heading) {}
}
