package com.evacuation.engine.security;

import com.evacuation.engine.model.entity.AppUser;
import com.evacuation.engine.model.enums.UserRole;
import com.evacuation.engine.repository.security.AppUserRepository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Seeds one admin and one demo user account on every boot.
 *
 * <p>Necessary, not optional, because {@code spring.jpa.hibernate.ddl-auto=create-drop} wipes
 * {@code app_users} along with every other table on every restart — without this, the very first
 * login after any restart would have no account to authenticate against. Runs after
 * {@link com.evacuation.engine.loader.GraphStartupRunner} in the same {@code ApplicationRunner}
 * phase; ordering between the two does not matter, since neither reads the other's state.
 *
 * <p>Idempotent by username rather than unconditional: {@code existsByUsername} is checked before
 * each insert, so re-running against a database that was not actually dropped (a future deployment
 * with {@code ddl-auto=validate}, say) does not throw on the unique constraint or duplicate an
 * account. Credentials are configurable via {@code app.security.seed.*} rather than hardcoded, so a
 * real deployment can override the defaults without a code change -- the same reasoning
 * {@link com.evacuation.engine.config.GraphEngineProperties} gives for its own operator levers.
 */
@Component
@Slf4j
public class UserSeedRunner implements ApplicationRunner {

    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;

    private final String adminUsername;
    private final String adminPassword;
    private final String userUsername;
    private final String userPassword;

    public UserSeedRunner(AppUserRepository appUserRepository, PasswordEncoder passwordEncoder,
                          @Value("${app.security.seed.admin-username:admin}") String adminUsername,
                          @Value("${app.security.seed.admin-password:admin123}") String adminPassword,
                          @Value("${app.security.seed.user-username:user}") String userUsername,
                          @Value("${app.security.seed.user-password:user123}") String userPassword) {
        this.appUserRepository = appUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
        this.userUsername = userUsername;
        this.userPassword = userPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedIfMissing(adminUsername, adminPassword, UserRole.ADMIN);
        seedIfMissing(userUsername, userPassword, UserRole.USER);
    }

    private void seedIfMissing(String username, String rawPassword, UserRole role) {
        if (appUserRepository.existsByUsername(username)) {
            log.info("Account '{}' already exists; not reseeding", username);
            return;
        }

        AppUser appUser = AppUser.builder()
                .username(username)
                .passwordHash(passwordEncoder.encode(rawPassword))
                .role(role)
                .enabled(true)
                .build();
        appUserRepository.save(appUser);

        log.info("Seeded {} account '{}'", role, username);
    }
}
