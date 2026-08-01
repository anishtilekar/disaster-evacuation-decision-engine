package com.evacuation.engine.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

/**
 * The authorization matrix for the user/admin split: who may call which endpoint, over one
 * {@link SecurityFilterChain}. Nothing in this app had any authentication before this class —
 * every endpoint was anonymous and world-writable — so this is new ground, not an extension of an
 * existing scheme.
 *
 * <p><strong>Two audiences, two rule tiers.</strong> {@code hasRole("ADMIN")} guards everything that
 * changes the world an evacuee is routed through — hazards, road blocks, disasters, shelters, batch
 * dispatch, LNS. {@code authenticated()} guards the read surface both roles need to render a map and
 * the one write a USER is allowed: submitting their own request and asking to be routed. Anonymous
 * access is limited to the login page and the handful of static assets a login page needs.
 *
 * <p><strong>CSRF stays on, deliberately.</strong> The frontend authenticates with a session cookie
 * and then issues JSON {@code fetch} POSTs, which is exactly the shape CSRF protection exists to
 * defend — disabling it here would be removing the protection because using it correctly takes a few
 * more lines, not because it is unneeded. {@link CookieCsrfTokenRepository#withHttpOnlyFalse()}
 * publishes the token as a readable {@code XSRF-TOKEN} cookie so client JS can echo it back as the
 * {@code X-XSRF-TOKEN} header on state-changing calls. The {@link CsrfTokenRequestAttributeHandler}
 * with a {@code null} request-attribute name opts out of Spring Security 6's deferred CSRF token
 * loading — without it, the cookie is not written until the token is actually rendered into a page
 * (i.e. never, for a JSON API with no server-rendered forms), and the first POST 403s. This is a
 * documented Spring Boot 3 / Spring Security 6 gotcha, wired here explicitly rather than discovered
 * later at demo time.
 *
 * <p><strong>Two authentication entry points, not one.</strong> This app serves both a JSON API and
 * HTML pages from the same filter chain. An unauthenticated {@code fetch} to {@code /api/**} should
 * get a plain {@code 401} it can branch on, not the login page's HTML masquerading as a JSON body;
 * an unauthenticated page load should get the normal browser redirect to the login page. Splitting
 * the entry point by path is what {@link #securityFilterChain} does with
 * {@code defaultAuthenticationEntryPointFor}.
 *
 * <p><strong>Login lands each role on its own page.</strong> Now that Part 4 has split the frontend
 * into {@code user-map.html} and {@code admin-map.html}, a single {@code defaultSuccessUrl} can no
 * longer be right for both roles, so {@link #roleBasedSuccessHandler()} reads the authenticated
 * principal's granted authority and redirects accordingly.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Sends an ADMIN to {@code /admin-map.html} and a USER to {@code /user-map.html} on successful
     * login — the one piece of role logic that cannot live in a static {@code requestMatchers} rule,
     * since it depends on who just authenticated rather than what URL they asked for.
     */
    @Bean
    public AuthenticationSuccessHandler roleBasedSuccessHandler() {
        return (request, response, authentication) -> {
            boolean isAdmin = authentication.getAuthorities().stream()
                    .anyMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN"));
            response.sendRedirect(isAdmin ? "/admin-map.html" : "/user-map.html");
        };
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                    AuthenticationSuccessHandler roleBasedSuccessHandler)
            throws Exception {
        CsrfTokenRequestAttributeHandler csrfRequestHandler = new CsrfTokenRequestAttributeHandler();
        csrfRequestHandler.setCsrfRequestAttributeName(null);

        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(csrfRequestHandler))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/css/**", "/js/**", "/error", "/favicon.ico").permitAll()

                        // Admin-only: everything that changes disasters, hazards, roads, shelters,
                        // or runs a batch/LNS pass -- reads of the same operational state (what's
                        // active right now) are gated the same way, since they exist for this same
                        // console to act on, not for a USER to see.
                        .requestMatchers(HttpMethod.POST, "/api/hazards").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/hazards").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/api/hazards/*/resolve").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/graph/blocked-roads").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/graph/blocked-roads").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/api/graph/blocked-roads/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/disasters/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/shelters/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/api/shelters/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/dispatch/plan").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/dispatch/improve").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/evacuation-requests").hasRole("ADMIN")
                        .requestMatchers("/admin-map.html").hasRole("ADMIN")

                        // Authenticated (either role): the read surface both pages render from, plus
                        // a USER's own write -- submit a request, ask to be routed.
                        .requestMatchers(HttpMethod.GET, "/api/graph").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/hazards/timeline").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/dispatch/plan/current").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/disasters").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/disasters/*/zones").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/evacuation-requests").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/evacuation-requests/mine").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/evacuation-requests/*/route").authenticated()
                        .requestMatchers("/user-map.html").authenticated()

                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginPage("/login.html")
                        .loginProcessingUrl("/login")
                        .successHandler(roleBasedSuccessHandler)
                        .permitAll())
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login.html?logout")
                        .permitAll())
                .exceptionHandling(ex -> ex
                        .defaultAuthenticationEntryPointFor(
                                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                                new AntPathRequestMatcher("/api/**"))
                        .defaultAuthenticationEntryPointFor(
                                new LoginUrlAuthenticationEntryPoint("/login.html"),
                                new AntPathRequestMatcher("/**")));

        return http.build();
    }
}
