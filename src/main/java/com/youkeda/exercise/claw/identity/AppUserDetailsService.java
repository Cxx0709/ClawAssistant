package com.youkeda.exercise.claw.identity;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class AppUserDetailsService implements UserDetailsService {

    private final AppUserRepository users;

    public AppUserDetailsService(AppUserRepository users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        AppUser account = users.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("账号不存在"));
        return User.withUsername(account.username())
                .password(account.passwordHash())
                .disabled(!account.enabled())
                .roles("USER")
                .build();
    }
}
