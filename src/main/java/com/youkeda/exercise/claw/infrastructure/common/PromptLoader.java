package com.youkeda.exercise.claw.infrastructure.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/**
 * 提示词文件加载工具
 *
 * 从 classpath 加载系统提示词文件，统一处理 IO 异常。
 * 避免 LLMClient / VisionClient / ImageClient 重复编码。
 */
@Component
public class PromptLoader {

    private static final Logger log = LoggerFactory.getLogger(PromptLoader.class);

    /**
     * 从 classpath 加载提示词文件
     *
     * @param path          classpath 路径（如 prompts/system-prompt.txt）
     * @param defaultPrompt 加载失败时的默认提示词
     * @return 提示词内容
     */
    public String load(String path, String defaultPrompt) {
        try {
            ClassPathResource resource = new ClassPathResource(path);
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                String content = reader.lines().collect(Collectors.joining("\n"));
                log.info("提示词文件加载完成 | path={} | chars={}", path, content.length());
                return content;
            }
        } catch (Exception e) {
            log.error("加载提示词文件失败 | path={}，使用默认提示词", path, e);
            return defaultPrompt;
        }
    }
}