package com.youkeda.exercise.claw.web;

import com.youkeda.exercise.claw.agent.skill.PendingToolAction;
import com.youkeda.exercise.claw.agent.skill.PendingToolCoordinator;
import com.youkeda.exercise.claw.identity.AuthenticatedUser;
import com.youkeda.exercise.claw.identity.UserExecutionContext;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 待确认工具 REST 入口（Phase 5 前端按钮确认）。
 *
 * <p>当 SafetyPolicy 拦截高风险工具并生成待确认操作后，前端通过以下接口：
 * <ul>
 *   <li>{@code GET /api/tools/pending} — 查询当前用户的待确认操作（无则返回 null）</li>
 *   <li>{@code POST /api/tools/pending/confirm} — 确认并执行原始 tool call</li>
 *   <li>{@code POST /api/tools/pending/cancel} — 取消待确认操作</li>
 * </ul>
 *
 * <p>与对话关键词确认（用户回复「确认/好的」）共用 {@link PendingToolCoordinator}，两条路径等价。
 */
@RestController
@RequestMapping("/api/tools/pending")
public class PendingToolController {

    private final PendingToolCoordinator pending;
    private final AuthenticatedUser users;
    private final UserExecutionContext userContext;

    public PendingToolController(PendingToolCoordinator pending,
                                 AuthenticatedUser users,
                                 UserExecutionContext userContext) {
        this.pending = pending;
        this.users = users;
        this.userContext = userContext;
    }

    /** 查询当前用户的待确认操作。 */
    @GetMapping
    public Map<String, Object> get(Authentication authentication) {
        String userId = users.require(authentication).id();
        PendingToolAction action = pending.findPending(userId);
        if (action == null || action.status() != PendingToolAction.Status.PENDING_CONFIRMATION) {
            return Map.of("pending", null);
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", action.id());
        view.put("toolName", action.toolName());
        view.put("displayName", PendingToolCoordinator.displayName(action.toolName()));
        view.put("argsPreview", truncateArgs(action.toolArguments()));
        view.put("expireAt", action.expireAt().toString());
        return Map.of("pending", view);
    }

    /** 确认并执行待确认操作。 */
    @PostMapping("/confirm")
    public Map<String, Object> confirm(Authentication authentication) {
        return act(authentication, true);
    }

    /** 取消待确认操作。 */
    @PostMapping("/cancel")
    public Map<String, Object> cancel(Authentication authentication) {
        return act(authentication, false);
    }

    private Map<String, Object> act(Authentication authentication, boolean isConfirm) {
        String userId = users.require(authentication).id();
        // 打开租户上下文，保证 tenant 隔离的服务（如 Qdrant 记忆）在工具执行时可用
        try (var ignored = userContext.open(userId)) {
            PendingToolCoordinator.Result result = isConfirm
                    ? pending.confirm(userId)
                    : pending.cancel(userId);
            if (!result.handled()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "当前没有待确认的操作，可能已超时或已被处理");
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", result.type().name());
            body.put("reply", result.userReply() == null ? "" : result.userReply());
            if (result.rawToolResult() != null) {
                body.put("rawResult", result.rawToolResult());
            }
            return body;
        }
    }

    private static String truncateArgs(String args) {
        if (args == null || args.isBlank()) return "{}";
        return args.length() <= 120 ? args : args.substring(0, 120) + "...";
    }
}
