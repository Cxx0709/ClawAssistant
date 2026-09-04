package com.youkeda.exercise.claw.web;

import com.youkeda.exercise.claw.agent.memory.longterm.*;
import com.youkeda.exercise.claw.identity.AuthenticatedUser;
import com.youkeda.exercise.claw.identity.UserExecutionContext;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

@RestController
@RequestMapping("/api/memories")
public class MemoryController {
    private final LongTermMemoryService memories;
    private final MemoryChangeStore changes;
    private final AuthenticatedUser users;
    private final UserExecutionContext context;

    public MemoryController(LongTermMemoryService memories, MemoryChangeStore changes,
                            AuthenticatedUser users, UserExecutionContext context) {
        this.memories = memories;
        this.changes = changes;
        this.users = users;
        this.context = context;
    }

    @GetMapping
    public Map<String, Object> list(Authentication authentication) {
        return scoped(authentication, () -> Map.of("items", memories.listAll(), "enabled", memories.isEnabled()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MemoryItem create(Authentication authentication, @RequestBody Edit request) {
        validate(request);
        return scoped(authentication, () -> memories.createManaged(request.category(), request.content().strip()));
    }

    @PutMapping("/{id}")
    public MemoryItem update(Authentication authentication, @PathVariable String id, @RequestBody Edit request) {
        validate(request);
        validateId(id);
        return scoped(authentication, () -> memories.updateManaged(id, request.category(), request.content().strip(),
                request.disabled(), request.expectedUpdatedAt()));
    }

    @DeleteMapping("/{id}")
    public Map<String, Boolean> delete(Authentication authentication, @PathVariable String id,
                                       @RequestParam Instant expectedUpdatedAt) {
        validateId(id);
        return scoped(authentication, () -> {
            memories.deleteManaged(id, expectedUpdatedAt);
            return Map.of("deleted", true);
        });
    }

    @GetMapping("/changes")
    public List<ChangeView> changes(Authentication authentication, @RequestParam String conversationId) {
        return scoped(authentication, () -> changes.recent(conversationId).stream()
                .map(change -> new ChangeView(change.id(), change.before() == null ? "ADDED" : "UPDATED", change.after()))
                .toList());
    }

    @PostMapping("/changes/{id}/undo")
    public Map<String, Boolean> undo(Authentication authentication, @PathVariable long id) {
        return scoped(authentication, () -> {
            memories.undoManaged(id);
            return Map.of("undone", true);
        });
    }

    private <T> T scoped(Authentication authentication, Supplier<T> operation) {
        String userId = users.require(authentication).id();
        try (var ignored = context.open(userId)) {
            return operation.get();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "记忆服务暂时不可用，请稍后重试", e);
        }
    }

    private void validate(Edit request) {
        if (request == null || request.category() == null || request.content() == null
                || request.content().isBlank() || request.content().length() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择分类，并填写 1–500 字的记忆内容");
        }
    }

    private void validateId(String id) {
        try { UUID.fromString(id); }
        catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "记忆编号无效"); }
    }

    public record Edit(MemoryCategory category, String content, boolean disabled, Instant expectedUpdatedAt) {}
    public record ChangeView(long id, String action, MemoryItem memory) {}
}
