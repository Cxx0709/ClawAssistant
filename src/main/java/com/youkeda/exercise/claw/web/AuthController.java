package com.youkeda.exercise.claw.web;

import com.youkeda.exercise.claw.identity.AppUser;
import com.youkeda.exercise.claw.identity.AppUserRepository;
import com.youkeda.exercise.claw.identity.AuthenticatedUser;
import com.youkeda.exercise.claw.identity.LegacyOwnerImporter;
import com.youkeda.exercise.claw.identity.UserProfileRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AppUserRepository users;
    private final UserProfileRepository profiles;
    private final LegacyOwnerImporter legacyOwnerImporter;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticatedUser authenticatedUser;

    public AuthController(AppUserRepository users,
                          UserProfileRepository profiles,
                          LegacyOwnerImporter legacyOwnerImporter,
                          PasswordEncoder passwordEncoder,
                          AuthenticatedUser authenticatedUser) {
        this.users = users;
        this.profiles = profiles;
        this.legacyOwnerImporter = legacyOwnerImporter;
        this.passwordEncoder = passwordEncoder;
        this.authenticatedUser = authenticatedUser;
    }

    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
    }

    @GetMapping("/setup-status")
    public Map<String, Boolean> setupStatus() {
        return Map.of("setupRequired", users.count() == 0);
    }

    @PostMapping("/setup")
    @ResponseStatus(HttpStatus.CREATED)
    public synchronized Map<String, Object> setup(@RequestBody SetupRequest request) {
        if (users.count() > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "系统已经完成初始化");
        }
        validate(request);
        LegacyOwnerImporter.LegacyOwner legacy = legacyOwnerImporter.findOwner();
        String userId = legacy != null && legacy.userId() != null && !legacy.userId().isBlank()
                ? legacy.userId() : UUID.randomUUID().toString();
        AppUser user = users.create(userId, request.username().trim(),
                passwordEncoder.encode(request.password()), request.displayName().trim());
        profiles.ensureProfile(user.id());
        if (legacy != null && legacy.schoolId() != null) {
            profiles.setSchoolId(user.id(), legacy.schoolId());
        }
        return Map.of("status", "SUCCESS", "legacyDataClaimed", legacy != null,
                "username", user.username());
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> register(@RequestBody SetupRequest request) {
        if (users.count() == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "请先创建首个管理员账户");
        }
        validate(request);
        AppUser user;
        try {
            user = users.create(UUID.randomUUID().toString(), request.username().trim(),
                    passwordEncoder.encode(request.password()), request.displayName().trim());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
        }
        profiles.ensureProfile(user.id());
        return Map.of("status", "SUCCESS", "username", user.username());
    }

    @GetMapping("/me")
    public Map<String, Object> me(Authentication authentication) {
        AppUser user = authenticatedUser.require(authentication);
        return Map.of("id", user.id(), "username", user.username(),
                "displayName", user.displayName());
    }

    private static void validate(SetupRequest request) {
        if (request == null || request.username() == null || request.username().trim().length() < 3) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "用户名至少 3 个字符");
        }
        if (request.password() == null || request.password().length() < 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "密码至少 8 个字符");
        }
        if (request.displayName() == null || request.displayName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "显示名称不能为空");
        }
    }

    public record SetupRequest(String username, String password, String displayName) {}
}
