package com.youkeda.exercise.claw.ai.image;

import com.youkeda.exercise.claw.ai.image.ImageClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 图片生成服务
 *
 * 职责：封装图片生成的业务逻辑，调用 ImageClient 完成模型交互
 */
@Service
public class ImageGenerationService {

    private static final Logger log = LoggerFactory.getLogger(ImageGenerationService.class);

    /** 图片生成 API 业务错误（如内容审核不通过）最大重试次数 */
    private static final int MAX_RETRIES = 3;

    private final ImageClient imageClient;

    public ImageGenerationService(ImageClient imageClient) {
        this.imageClient = imageClient;
    }

    /**
     * 根据提示词生成图片，返回图片 URL
     *
     * @param prompt 图片描述提示词
     * @return 生成的图片 URL，失败时返回 null
     */
    public String generate(String prompt) {
        log.info("ImageGenerationService 开始生成 | prompt={}", prompt);

        int attempt = 0;
        while (attempt < MAX_RETRIES) {
            try {
                String imageUrl = imageClient.generateImage(prompt);
                if (imageUrl != null) {
                    log.info("ImageGenerationService 生成完成 | url={}", imageUrl);
                    return imageUrl;
                }
                // ImageClient 返回 null（非 API 业务错误，重试无意义）
                log.warn("ImageGenerationService 生成失败（返回空）");
                return null;
            } catch (ImageClientException e) {
                attempt++;
                if (attempt >= MAX_RETRIES) {
                    log.error("图片生成失败，已达最大重试次数 {} | errorCode={}", MAX_RETRIES, e.getErrorCode());
                    return null;
                }
                log.warn("图片生成失败 (第{}/{}) | errorCode={}，{}ms 后重试",
                        attempt, MAX_RETRIES, e.getErrorCode(), 2000L * attempt);
                try {
                    Thread.sleep(2000L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            } catch (Exception e) {
                log.error("ImageGenerationService 生成异常", e);
                return null;
            }
        }
        return null;
    }
}
