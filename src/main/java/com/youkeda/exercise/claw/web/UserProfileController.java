package com.youkeda.exercise.claw.web;

import com.youkeda.exercise.claw.identity.AuthenticatedUser;
import com.youkeda.exercise.claw.identity.UserProfileRepository;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * 用户资料 API：邮箱绑定、邮件通知开关等。
 *
 * <p>所有接口按当前登录用户隔离，不需要传 userId。
 */
@RestController
@RequestMapping("/api/profile")
public class UserProfileController {

    /** 简单邮箱格式校验：xxx@xxx.xxx */
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    private final UserProfileRepository profiles;
    private final AuthenticatedUser authenticatedUser;

    public UserProfileController(UserProfileRepository profiles,
                                 AuthenticatedUser authenticatedUser) {
        this.profiles = profiles;
        this.authenticatedUser = authenticatedUser;
    }

    /** 获取当前用户资料（邮箱、通知开关等）。 */
    @GetMapping
    public Map<String, Object> getProfile(Authentication authentication) {
        String userId = authenticatedUser.require(authentication).id();
        profiles.ensureProfile(userId);
        return Map.of(
                "email", profiles.getEmail(userId) == null ? "" : profiles.getEmail(userId),
                "emailNotificationsEnabled", profiles.emailNotificationsEnabled(userId),
                "notificationsEnabled", profiles.notificationsEnabled(userId)
        );
    }

    /**
     * 设置/更新邮箱地址。
     * RequestBody: {"email": "xxx@qq.com"}
     */
    @PutMapping("/email")
    public Map<String, Object> setEmail(Authentication authentication,
                                         @RequestBody Map<String, String> body) {
        String userId = authenticatedUser.require(authentication).id();
        String email = body.get("email");
        if (email == null || email.isBlank()) {
            return Map.of("success", false, "error", "邮箱地址不能为空");
        }
        String trimmed = email.trim();
        if (!EMAIL_PATTERN.matcher(trimmed).matches()) {
            return Map.of("success", false, "error", "邮箱格式不正确，请检查后重试");
        }
        profiles.setEmail(userId, trimmed);
        return Map.of("success", true, "email", trimmed);
    }

    /** 清除已绑定的邮箱。 */
    @DeleteMapping("/email")
    public Map<String, Object> clearEmail(Authentication authentication) {
        String userId = authenticatedUser.require(authentication).id();
        profiles.setEmail(userId, null);
        return Map.of("success", true);
    }

    /**
     * 开启或关闭邮件通知。
     * RequestBody: {"enabled": true}
     */
    @PutMapping("/email-notifications")
    public Map<String, Object> setEmailNotifications(Authentication authentication,
                                                       @RequestBody Map<String, Object> body) {
        String userId = authenticatedUser.require(authentication).id();
        Object enabledObj = body.get("enabled");
        if (enabledObj == null) {
            return Map.of("success", false, "error", "缺少 enabled 参数");
        }
        boolean enabled = Boolean.parseBoolean(enabledObj.toString());
        profiles.setEmailNotificationsEnabled(userId, enabled);
        return Map.of("success", true, "emailNotificationsEnabled", enabled);
    }
}
