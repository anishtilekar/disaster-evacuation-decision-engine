package com.evacuation.engine.security;

import com.evacuation.engine.model.entity.AppUser;
import com.evacuation.engine.repository.security.AppUserRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Resolves a login name to Spring Security's {@link UserDetails}, the one seam
 * {@code DaoAuthenticationProvider} needs to authenticate a form-login submission against
 * {@link AppUser}. The role becomes a single {@code ROLE_*} authority — {@code UserRole.ADMIN}
 * becomes {@code ROLE_ADMIN} — which is the prefix {@code hasRole(...)} in
 * {@link SecurityConfig} expects.
 */
@Service
@RequiredArgsConstructor
public class AppUserDetailsService implements UserDetailsService {

    private final AppUserRepository appUserRepository;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        AppUser appUser = appUserRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("No account for username " + username));

        return User.builder()
                .username(appUser.getUsername())
                .password(appUser.getPasswordHash())
                .disabled(!Boolean.TRUE.equals(appUser.getEnabled()))
                .roles(appUser.getRole().name())
                .build();
    }
}
