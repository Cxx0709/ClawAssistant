package com.youkeda.exercise.claw.web;

import com.youkeda.exercise.claw.ai.image.ImageClient;
import com.youkeda.exercise.claw.ai.image.ImageGenerationService;
import com.youkeda.exercise.claw.artifact.ArtifactKind;
import com.youkeda.exercise.claw.artifact.ArtifactService;
import com.youkeda.exercise.claw.artifact.GeneratedArtifact;
import com.youkeda.exercise.claw.identity.AppUser;
import com.youkeda.exercise.claw.identity.AuthenticatedUser;
import com.youkeda.exercise.claw.identity.UserExecutionContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ImageBoardController.class)
@Import({SecurityConfig.class, UserExecutionContext.class})
class ImageBoardControllerTest {
    @Autowired MockMvc mvc;
    @MockBean ImageGenerationService imageGenerationService;
    @MockBean ImageClient imageClient;
    @MockBean ArtifactService artifacts;
    @MockBean AuthenticatedUser users;

    @Test
    void anonymousRequestIsUnauthorized() throws Exception {
        mvc.perform(post("/api/workspace/images/generate").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"西湖\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "alice")
    void missingPromptAndSourceArtifactIdIsBadRequest() throws Exception {
        when(users.require(any())).thenReturn(appUser("tenant-a", "alice"));

        mvc.perform(post("/api/workspace/images/generate").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"style\":\"INK\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @WithMockUser(username = "alice")
    void unsupportedStyleIsBadRequest() throws Exception {
        when(users.require(any())).thenReturn(appUser("tenant-a", "alice"));

        mvc.perform(post("/api/workspace/images/generate").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"西湖\",\"style\":\"OIL\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("不支持的图片风格：OIL"));
    }

    @Test
    @WithMockUser(username = "alice")
    void missingSourceArtifactReturnsNotFound() throws Exception {
        when(users.require(any())).thenReturn(appUser("tenant-a", "alice"));
        when(artifacts.load("tenant-a", "nope")).thenReturn(Optional.empty());

        mvc.perform(post("/api/workspace/images/generate").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceArtifactId\":\"nope\",\"style\":\"INK\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "alice")
    void tenantIsolationOtherUsersArtifactIsNotFound() throws Exception {
        when(users.require(any())).thenReturn(appUser("tenant-b", "bob"));
        when(artifacts.load("tenant-b", "src")).thenReturn(Optional.empty());

        mvc.perform(post("/api/workspace/images/generate").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceArtifactId\":\"src\",\"style\":\"INK\"}"))
                .andExpect(status().isNotFound());

        verify(artifacts).load("tenant-b", "src");
    }

    @Test
    @WithMockUser(username = "alice")
    void generatesFromSourceArtifactAndAppendsStyleSuffix() throws Exception {
        when(users.require(any())).thenReturn(appUser("tenant-a", "alice"));
        // 原 prompt 带历史风格标记，换风格时必须剥离，不允许叠罗汉
        GeneratedArtifact source = GeneratedArtifact.of("src", "tenant-a", ArtifactKind.IMAGE,
                "image/png", "generated-image.png", 1024, "西湖全景 ·[新海诚风]重绘", "/api/artifacts/src");
        ArtifactService.StoredArtifact stored = mock(ArtifactService.StoredArtifact.class);
        when(stored.metadata()).thenReturn(source);
        when(artifacts.load("tenant-a", "src")).thenReturn(Optional.of(stored));
        when(imageGenerationService.generate(anyString())).thenReturn("http://img/generated.png");
        when(imageClient.downloadImage("http://img/generated.png")).thenReturn(new byte[]{1, 2, 3});
        GeneratedArtifact created = GeneratedArtifact.of("new-id", "tenant-a", ArtifactKind.IMAGE,
                "image/png", "generated-image.png", 3, "西湖全景 ·[水墨]重绘", "/api/artifacts/new-id");
        when(artifacts.store(eq("tenant-a"), eq(ArtifactKind.IMAGE), any(), anyString(), anyString(), anyString()))
                .thenReturn(created);

        mvc.perform(post("/api/workspace/images/generate").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceArtifactId\":\"src\",\"style\":\"INK\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.artifact.id").value("new-id"))
                .andExpect(jsonPath("$.artifact.description").value("西湖全景 ·[水墨]重绘"))
                .andExpect(jsonPath("$.style").value("INK"));

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(imageGenerationService).generate(promptCaptor.capture());
        // 原 prompt 已剥离新海诚后缀，且拼上水墨模板
        assertThat(promptCaptor.getValue())
                .startsWith("西湖全景")
                .doesNotContain("新海诚")
                .contains("水墨");
    }

    @Test
    @WithMockUser(username = "alice")
    void uploadedImageWithoutPromptIsRejected() throws Exception {
        when(users.require(any())).thenReturn(appUser("tenant-a", "alice"));
        GeneratedArtifact source = GeneratedArtifact.of("up", "tenant-a", ArtifactKind.IMAGE,
                "image/png", "upload.png", 1024, "用户上传", "/api/artifacts/up");
        ArtifactService.StoredArtifact stored = mock(ArtifactService.StoredArtifact.class);
        when(stored.metadata()).thenReturn(source);
        when(artifacts.load("tenant-a", "up")).thenReturn(Optional.of(stored));

        mvc.perform(post("/api/workspace/images/generate").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceArtifactId\":\"up\",\"style\":\"FLAT\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("这张图没有生成描述，请在对话里描述想要的画面"));

        verify(imageGenerationService, never()).generate(anyString());
    }

    @Test
    @WithMockUser(username = "alice")
    void generationFailureReturnsBadGateway() throws Exception {
        when(users.require(any())).thenReturn(appUser("tenant-a", "alice"));
        when(imageGenerationService.generate(anyString())).thenReturn(null);

        mvc.perform(post("/api/workspace/images/generate").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"西湖\",\"style\":\"REALISTIC\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void concurrentGenerationForSameUserReturnsConflict() throws Exception {
        // 后台线程不继承 @WithMockUser 的 ThreadLocal 安全上下文，改用请求级 user() 后处理器
        when(users.require(any())).thenReturn(appUser("tenant-a", "alice"));
        CountDownLatch generating = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(imageGenerationService.generate(anyString())).thenAnswer(invocation -> {
            generating.countDown();
            release.await(5, TimeUnit.SECONDS);
            return "http://img/slow.png";
        });
        when(imageClient.downloadImage(anyString())).thenReturn(new byte[]{1});
        when(artifacts.store(anyString(), any(), any(), anyString(), anyString(), anyString()))
                .thenReturn(GeneratedArtifact.of("id", "tenant-a", ArtifactKind.IMAGE,
                        "image/png", "generated-image.png", 1, "x ·[写实]重绘", "/api/artifacts/id"));

        // 第一个请求阻塞在慢生成上，放后台线程执行；控制器同步返回前 MockMvc 会一直等待
        CompletableFuture<MvcResult> first = CompletableFuture.supplyAsync(() -> {
            try {
                MvcResult result = mvc.perform(post("/api/workspace/images/generate")
                                        .with(csrf()).with(user("alice"))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"prompt\":\"西湖\",\"style\":\"REALISTIC\"}"))
                        .andExpect(status().isOk())
                        .andReturn();
                assertThat(result.getResponse().getStatus()).isEqualTo(200);
                return result;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertThat(generating.await(5, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Boolean> conflict = CompletableFuture.supplyAsync(() -> {
            try {
                MvcResult second = mvc.perform(post("/api/workspace/images/generate")
                                .with(csrf()).with(user("alice"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"prompt\":\"西湖\",\"style\":\"REALISTIC\"}"))
                        .andExpect(status().isConflict())
                        .andReturn();
                return second.getResponse().getStatus() == 409;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertThat(conflict.get(5, TimeUnit.SECONDS)).isTrue();
        release.countDown();
        first.get(10, TimeUnit.SECONDS);
    }

    private static AppUser appUser(String id, String username) {
        return new AppUser(id, username, "hash", username, true, Instant.now());
    }
}
