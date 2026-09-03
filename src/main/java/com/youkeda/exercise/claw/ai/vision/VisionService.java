package com.youkeda.exercise.claw.ai.vision;

import com.youkeda.exercise.claw.ai.vision.VisionClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 视觉理解服务
 *
 * 职责：封装图片分析的业务逻辑，调用 VisionClient 完成多模态模型交互
 */
@Service
public class VisionService {

    private static final Logger log = LoggerFactory.getLogger(VisionService.class);

    private final VisionClient visionClient;

    public VisionService(VisionClient visionClient) {
        this.visionClient = visionClient;
    }

    /**
     * 分析图片内容
     *
     * @param imageUrl 图片 URL（支持 HTTP URL 或 base64 data URL）
     * @param question 用户对图片的提问（可选，为 null 时使用默认描述提示）
     * @return 分析结果，失败时返回 null
     */
    public String analyze(String imageUrl, String question) {
        return analyze(imageUrl, question, null);
    }

    /**
     * 分析图片内容（可指定采样温度）
     *
     * @param imageUrl    图片 URL（支持 HTTP URL 或 base64 data URL）
     * @param question    用户对图片的提问（可选，为 null 时使用默认描述提示）
     * @param temperature 采样温度，null 表示不指定（走模型默认）；对账/校验类任务建议传 0
     * @return 分析结果，失败时返回 null
     */
    public String analyze(String imageUrl, String question, Double temperature) {
        log.info("VisionService 开始分析 | imageUrlLen={} | question={}",
                imageUrl != null ? imageUrl.length() : 0, question);

        try {
            String reply = visionClient.analyzeImage(imageUrl, question, temperature);
            if (reply == null || reply.isEmpty()) {
                log.warn("VisionService 分析结果为空");
                return null;
            }
            log.info("VisionService 分析完成");
            return reply;
        } catch (Exception e) {
            log.error("VisionService 分析异常 | error={}", e.getMessage());
            return null;
        }
    }
}