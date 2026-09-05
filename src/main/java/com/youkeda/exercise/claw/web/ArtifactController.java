package com.youkeda.exercise.claw.web;

import com.youkeda.exercise.claw.artifact.ArtifactKind;
import com.youkeda.exercise.claw.artifact.ArtifactService;
import com.youkeda.exercise.claw.artifact.GeneratedArtifact;
import com.youkeda.exercise.claw.identity.AuthenticatedUser;
import com.youkeda.exercise.claw.infrastructure.document.FileParseService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

@RestController
@RequestMapping("/api/artifacts")
public class ArtifactController {

    private final ArtifactService artifacts;
    private final AuthenticatedUser authenticatedUser;
    private final FileParseService fileParseService;

    public ArtifactController(ArtifactService artifacts, AuthenticatedUser authenticatedUser,
                              FileParseService fileParseService) {
        this.artifacts = artifacts;
        this.authenticatedUser = authenticatedUser;
        this.fileParseService = fileParseService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public GeneratedArtifact upload(Authentication authentication,
                                    @RequestParam("file") MultipartFile file) throws IOException {
        String userId = authenticatedUser.require(authentication).id();
        String mime = file.getContentType() == null ? "application/octet-stream" : file.getContentType();
        ArtifactKind kind = mime.startsWith("image/") ? ArtifactKind.IMAGE
                : mime.startsWith("audio/") ? ArtifactKind.AUDIO : ArtifactKind.FILE;
        return artifacts.store(userId, kind, file.getBytes(), mime,
                file.getOriginalFilename(), "用户上传");
    }

    @GetMapping
    public List<GeneratedArtifact> list(Authentication authentication,
                                        @RequestParam(defaultValue = "50") int limit) {
        return artifacts.list(authenticatedUser.require(authentication).id(), limit);
    }

    @GetMapping("/{id}")
    public ResponseEntity<FileSystemResource> download(Authentication authentication,
                                                       @PathVariable String id,
                                                       @RequestParam(defaultValue = "false") boolean download) {
        String userId = authenticatedUser.require(authentication).id();
        ArtifactService.StoredArtifact stored = artifacts.load(userId, id)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(stored.metadata().mimeType()));
        headers.setContentLength(stored.metadata().size());
        ContentDisposition.Builder disposition = download
                ? ContentDisposition.attachment() : ContentDisposition.inline();
        headers.setContentDisposition(disposition
                .filename(stored.metadata().fileName(), StandardCharsets.UTF_8).build());
        return ResponseEntity.ok().headers(headers).body(new FileSystemResource(stored.path()));
    }

    @GetMapping("/{id}/preview")
    public ArtifactPreview preview(Authentication authentication, @PathVariable String id) throws IOException {
        String userId = authenticatedUser.require(authentication).id();
        ArtifactService.StoredArtifact stored = artifacts.load(userId, id)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND));
        var parsed = fileParseService.parse(Files.readAllBytes(stored.path()), stored.metadata().fileName());
        if (parsed == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "无法预览此文件");
        }
        return new ArtifactPreview(stored.metadata().fileName(), stored.metadata().mimeType(), parsed.text());
    }

    public record ArtifactPreview(String fileName, String mimeType, String content) {}
}
