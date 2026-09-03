package com.youkeda.exercise.claw.ai.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentChunkerTest {

    private final DocumentChunker chunker = new DocumentChunker();

    @Test
    void preservesMarkdownHeadingHierarchyFromH1ToH6() {
        String markdown = """
                # 差旅制度
                总则。
                ## 费用标准
                费用说明。
                ### 酒店
                酒店说明。
                #### 一线城市
                上限说明。
                """;

        List<DocumentChunker.Chunk> chunks = chunker.chunkMarkdown(
                markdown, "doc-1", "travel.md");

        assertEquals(4, chunks.size());
        assertEquals("差旅制度", chunks.get(0).heading());
        assertEquals("差旅制度 > 费用标准", chunks.get(1).heading());
        assertEquals("差旅制度 > 费用标准 > 酒店", chunks.get(2).heading());
        assertEquals("差旅制度 > 费用标准 > 酒店 > 一线城市", chunks.get(3).heading());
    }

    @Test
    void markdownCarriesRealOverlapAcrossHeadingBoundaries() {
        String markdown = "# 第一章\n" + "A".repeat(1800) + "\n## 第二章\n第二章正文";

        List<DocumentChunker.Chunk> chunks = chunker.chunkMarkdown(
                markdown, "doc-markdown", "rules.md");

        assertEquals(2, chunks.size());
        String previousTail = chunks.get(0).content().substring(
                chunks.get(0).content().length() - 250);
        assertTrue(chunks.get(1).content().startsWith(previousTail));
        assertFalse(chunks.get(1).content().startsWith(chunks.get(0).content()));
    }

    @Test
    void plainTextRetainsRealOverlapAcrossChunkBoundary() {
        String firstParagraph = "A".repeat(1800) + "-boundary-marker-" + "B".repeat(180);
        String secondParagraph = "C".repeat(600);

        List<DocumentChunker.Chunk> chunks = chunker.chunkPlainText(
                firstParagraph + "\n\n" + secondParagraph, "doc-2", "rules.txt");

        assertTrue(chunks.size() >= 2);
        String previousTail = chunks.get(0).content().substring(
                Math.max(0, chunks.get(0).content().length() - 250));
        assertFalse(previousTail.isBlank());
        assertTrue(chunks.get(1).content().startsWith(previousTail),
                "后一 chunk 必须以真实的前块尾部 overlap 开始");
    }
}
