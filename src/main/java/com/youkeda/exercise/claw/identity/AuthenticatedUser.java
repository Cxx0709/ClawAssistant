package com.youkeda.exercise.claw.identity;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/** Resolves the opaque application user id from a verified Spring Security principal. */
@Component
public class AuthenticatedUser {

    private final AppUserRepository users;

    public AuthenticatedUser(AppUserRepository users) {
        this.users = users;
    }

    public AppUser require(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            throw new org.springframework.security.authentication.AuthenticationCredentialsNotFoundException(
                    "请先登录");
        }
        return users.findByUsername(authentication.getName())
                .orElseThrow(() -> new org.springframework.security.authentication.AuthenticationCredentialsNotFoundException(
                        "登录账号不存在"));
    }
}
