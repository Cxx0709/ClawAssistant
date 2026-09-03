package com.youkeda.exercise.claw.ai.retrieval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 启动时自动导入 classpath:knowledge/*.md，只在对应 skill 没有已有知识时才导入。
 */
@Component
public class KnowledgeAutoImporter {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeAutoImporter.class);

    private final SkillKnowledgeImportService importService;

    public KnowledgeAutoImporter(SkillKnowledgeImportService importService) {
        this.importService = importService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath:knowledge/*.md");
            for (Resource resource : resources) {
                String filename = resource.getFilename();
                if (filename == null || !filename.endsWith(".md")) continue;
                String skillName = filename.replace("-knowledge.md", "").replace(".md", "");
                try {
                    // 跳过已有知识的 skill
                    KnowledgeStoreStatus status = importService.status(skillName);
                    if (status.pointCount() > 0) {
                        log.debug("Auto-import skipped: skill={} already has {} points", skillName, status.pointCount());
                        continue;
                    }
                    String content = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    KnowledgeImportResult result = importService.importDocument(
                            skillName, content, filename, "MARKDOWN", "1.0");
                    log.info("Auto-imported knowledge: skill={} chunks={}", skillName, result.successCount());
                } catch (IllegalArgumentException e) {
                    log.debug("Auto-import skipped: skill={} reason={}", skillName, e.getMessage());
                } catch (Exception e) {
                    log.warn("Auto-import failed: skill={} reason={}", skillName, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.debug("No knowledge files to auto-import: {}", e.getMessage());
        }
    }
}
