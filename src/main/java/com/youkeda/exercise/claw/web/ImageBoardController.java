package com.youkeda.exercise.claw.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.youkeda.exercise.claw.ai.image.ImageClient;
import com.youkeda.exercise.claw.ai.image.ImageGenerationService;
import com.youkeda.exercise.claw.artifact.ArtifactKind;
import com.youkeda.exercise.claw.artifact.ArtifactService;
import com.youkeda.exercise.claw.artifact.GeneratedArtifact;
import com.youkeda.exercise.claw.identity.AuthenticatedUser;
import com.youkeda.exercise.claw.identity.UserExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 生成图片看板 · 换风格重画接口
 *
 * <p>工作台图片看板的"风格胶囊"入口：以已有产物的原 prompt（存在 description 字段）
 * 拼接风格模板后重新生成，纯 REST 确定性操作，不走 LLM 对话回合。
 *
 * <p>产物 description 约定：{@code "{原prompt} ·[{风格文案}]重绘"}。
 * 提取原 prompt 时按 " ·[" 截断，避免多次换风格后缀叠罗汉。
 */
@RestController
@RequestMapping("/api/workspace/images")
public class ImageBoardController {

    private static final Logger log = LoggerFactory.getLogger(ImageBoardController.class);

    /** 单次生成超时（图片生成约 20-60s，含内部重试留出余量） */
    private static final long GENERATE_TIMEOUT_SECONDS = 90L;

    /** 风格后缀标记（description 中的分隔符，勿改：前端/重绘提取都依赖它） */
    static final String STYLE_MARKER = " ·[";

    /** 同一用户同时只允许一个生成任务，重复请求返回 409 */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    /** 生成在独立线程执行，超时后可中断，不占 Tomcat 工作线程之外的额外长任务 */
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "image-board-generate");
        t.setDaemon(true);
        return t;
    });

    private final ImageGenerationService imageGenerationService;
    private final ImageClient imageClient;
    private final ArtifactService artifacts;
    private final AuthenticatedUser users;
    private final UserExecutionContext context;

    public ImageBoardController(ImageGenerationService imageGenerationService,
                                ImageClient imageClient,
                                ArtifactService artifacts,
                                AuthenticatedUser users,
                                UserExecutionContext context) {
        this.imageGenerationService = imageGenerationService;
        this.imageClient = imageClient;
        this.artifacts = artifacts;
        this.users = users;
        this.context = context;
    }

    @PostMapping("/generate")
    public ResponseEntity<Map<String, Object>> generate(@RequestBody JsonNode body,
                                                        Authentication authentication) {
        String userId = users.require(authentication).id();
        try (var ignored = context.open(userId)) {
            return doGenerate(userId, body);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("图片看板生成失败 | userId={} | error={}", userId, e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "图片生成暂时不可用，请稍后重试", e);
        }
    }

    private ResponseEntity<Map<String, Object>> doGenerate(String userId, JsonNode body) {
        if (!inFlight.add(userId)) {
            return conflict("正在生成中，请等待完成后再试");
        }
        try {
            String styleKey = text(body, "style");
            ImageStyle style = ImageStyle.fromKey(styleKey);
            if (styleKey != null && !styleKey.isBlank() && style == null) {
                return badRequest("不支持的图片风格：" + styleKey);
            }

            String sourceArtifactId = text(body, "sourceArtifactId");
            String explicitPrompt = text(body, "prompt");
            if ((sourceArtifactId == null || sourceArtifactId.isBlank())
                    && (explicitPrompt == null || explicitPrompt.isBlank())) {
                return badRequest("缺少生成描述：请提供 prompt 或 sourceArtifactId");
            }

            String basePrompt = explicitPrompt;
            if (basePrompt == null || basePrompt.isBlank()) {
                GeneratedArtifact source = artifacts.load(userId, sourceArtifactId)
                        .map(ArtifactService.StoredArtifact::metadata)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "原图不存在"));
                if (source.kind() != ArtifactKind.IMAGE) {
                    return badRequest("源产物不是图片，无法换风格重画");
                }
                basePrompt = stripStyleMarker(source.description());
                if (basePrompt.isBlank() || "用户上传".equals(basePrompt.trim())) {
                    return badRequest("这张图没有生成描述，请在对话里描述想要的画面");
                }
            }

            String finalPrompt = style == null ? basePrompt : basePrompt + "，" + style.promptSuffix;
            String description = style == null
                    ? basePrompt + STYLE_MARKER + "重新生成]重绘"
                    : basePrompt + STYLE_MARKER + style.label + "]重绘";

            GeneratedArtifact artifact = generateWithTimeout(userId, finalPrompt, description);
            if (artifact == null) {
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                        .body(Map.of("error", "图片生成失败，请稍后重试"));
            }
            return ResponseEntity.ok(Map.of(
                    "artifact", artifact,
                    "style", style == null ? "" : style.key()));
        } finally {
            inFlight.remove(userId);
        }
    }

    /** 独立线程执行生成 + 下载，90s 超时中断；返回 null 表示失败 */
    private GeneratedArtifact generateWithTimeout(String userId, String finalPrompt, String description) {
        Future<GeneratedArtifact> future = executor.submit(() -> {
            String imageUrl = imageGenerationService.generate(finalPrompt);
            if (imageUrl == null) return null;
            byte[] bytes = imageClient.downloadImage(imageUrl);
            if (bytes == null || bytes.length == 0) {
                log.warn("图片看板重画下载失败 | userId={}", userId);
                return null;
            }
            return artifacts.store(userId, ArtifactKind.IMAGE, bytes, "image/png",
                    "generated-image.png", description);
        });
        try {
            return future.get(GENERATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.error("图片看板生成超时（{}s）| userId={}", GENERATE_TIMEOUT_SECONDS, userId);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return null;
        } catch (Exception e) {
            log.error("图片看板生成异常 | userId={} | error={}", userId, e.getMessage(), e);
            return null;
        }
    }

    /** 剥离历史风格标记，拿到干净的原 prompt */
    static String stripStyleMarker(String description) {
        if (description == null) return "";
        int idx = description.indexOf(STYLE_MARKER);
        return idx >= 0 ? description.substring(0, idx).trim() : description.trim();
    }

    private static ResponseEntity<Map<String, Object>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }

    private static ResponseEntity<Map<String, Object>> conflict(String message) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", message));
    }

    private static String text(JsonNode body, String field) {
        JsonNode node = body == null ? null : body.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    /** 风格预设：key 与前端胶囊对齐，label 同时用作 description 标记 */
    enum ImageStyle {
        SHINKAI("新海诚风", "新海诚动画电影风格，唯美光影，高饱和天空，细腻云层"),
        FLAT("扁平插画风", "扁平插画风格，简洁几何图形，柔和配色，无描边"),
        INK("水墨", "中国水墨画风格，大量留白，写意笔触"),
        REALISTIC("写实", "写实摄影风格，自然光线，高清细节");

        final String label;
        final String promptSuffix;

        ImageStyle(String label, String promptSuffix) {
            this.label = label;
            this.promptSuffix = promptSuffix;
        }

        String key() {
            return name();
        }

        /** key 不区分大小写；空/null 表示"原样重画"（返回 null） */
        static ImageStyle fromKey(String key) {
            if (key == null || key.isBlank() || "NONE".equalsIgnoreCase(key.trim())) return null;
            for (ImageStyle s : values()) {
                if (s.name().equalsIgnoreCase(key.trim())) return s;
            }
            return null;
        }
    }
}
