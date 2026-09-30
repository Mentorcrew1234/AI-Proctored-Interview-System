package com.project.proctorinterview.config;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.DefaultAuthenticationEventPublisher;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.project.proctorinterview.auth.AppUserDetailsService;
import com.project.proctorinterview.auth.JwtAuthenticationFilter;

/**
 * Two independent filter chains:
 *
 * <ol>
 *   <li><b>/api/**</b> - stateless, JWT bearer tokens, JSON 401/403 responses.
 *       Used by the React exam screen and any REST client.</li>
 *   <li><b>everything else</b> - classic session + form login, used by the
 *       server-rendered Thymeleaf pages for admin and recruiter.</li>
 * </ol>
 *
 * Splitting them keeps each one simple: no redirect-to-login on an API call,
 * and no bearer-token handling on an HTML page.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain apiSecurityChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter) throws Exception {
        applySecurityHeaders(http);
        http
                .securityMatcher("/api/**")
                // Stateless bearer-token API: no session to ride, so no CSRF risk.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/login").permitAll()
                        .requestMatchers("/api/users/**").hasRole("ADMIN")
                        .requestMatchers("/api/interviews/**").hasAnyRole("ADMIN", "RECRUITER")
                        .requestMatchers("/api/exam/**", "/api/interview-sessions/**").hasRole("CANDIDATE")
                        .anyRequest().authenticated())
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authEx) -> writeJsonError(
                                response, HttpStatus.UNAUTHORIZED, "Authentication required"))
                        .accessDeniedHandler((request, response, deniedEx) -> writeJsonError(
                                response, HttpStatus.FORBIDDEN, "You do not have access to this resource")));
        return http.build();
    }

    /**
     * Response headers applied to both chains.
     *
     * <p><b>What the CSP here does and does not do.</b> It keeps
     * {@code 'unsafe-inline'} for scripts and styles, because the Thymeleaf
     * pages carry inline {@code <script>} blocks and around 150 inline
     * {@code style} attributes; and it keeps {@code 'unsafe-eval'} plus
     * {@code 'wasm-unsafe-eval'}, because TensorFlow.js and the MediaPipe WASM
     * runtime need them to run the detectors at all.
     *
     * <p>So this policy does <b>not</b> stop injected script from executing. What
     * it does do is pin every origin to {@code 'self'}: an injected script
     * cannot load code from an attacker's host, cannot post data to one, cannot
     * be framed for clickjacking, and cannot rewrite the base URI or retarget a
     * form. That is a real reduction in what a successful injection is worth,
     * and it costs nothing. Removing the two unsafe directives would mean
     * rewriting every inline handler and style first - worth doing, and
     * deliberately not done as a side effect of this change.
     *
     * <p>{@code camera} and {@code microphone} are allowed on {@code self}
     * because the interview genuinely needs them; everything else in the
     * permissions policy is switched off.
     */
    private static void applySecurityHeaders(HttpSecurity http) throws Exception {
        http.headers(headers -> headers
                .contentSecurityPolicy(csp -> csp.policyDirectives(String.join("; ",
                        "default-src 'self'",
                        // See the note above: unsafe-* are load-bearing here.
                        "script-src 'self' 'unsafe-inline' 'unsafe-eval' 'wasm-unsafe-eval' https://static.cloudflareinsights.com",
                        "style-src 'self' 'unsafe-inline'",
                        // data: and blob: carry the candidate's own camera
                        // frames and the model files, all same-origin.
                        "img-src 'self' data: blob:",
                        "media-src 'self' blob:",
                        "worker-src 'self' blob:",
                        "connect-src 'self' https://cloudflareinsights.com",
                        "font-src 'self' data:",
                        "object-src 'none'",
                        "base-uri 'self'",
                        "form-action 'self'",
                        "frame-ancestors 'self'")))
                // Stops a MIME sniff turning an uploaded file into script.
                .contentTypeOptions(withDefaults -> {
                })
                .referrerPolicy(referrer -> referrer.policy(
                        ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                .permissionsPolicyHeader(permissions -> permissions.policy(String.join(", ",
                        "camera=(self)",
                        "microphone=(self)",
                        "geolocation=()",
                        "payment=()",
                        "usb=()",
                        "interest-cohort=()")))
                // Only meaningful over HTTPS, which is required anyway because
                // getUserMedia refuses to run without a secure context.
                .httpStrictTransportSecurity(hsts -> hsts
                        .includeSubDomains(true)
                        .maxAgeInSeconds(31536000)));
    }

    @Bean
    SecurityFilterChain webSecurityChain(HttpSecurity http) throws Exception {
        applySecurityHeaders(http);
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/", "/login", "/error").permitAll()
                        // Reachable signed out, necessarily: someone who cannot
                        // log in must not be redirected to the login page.
                        .requestMatchers("/forgot-password", "/reset-password").permitAll()
                        .requestMatchers("/css/**", "/js/**", "/images/**", "/favicon.ico").permitAll()
                        // The React exam bundle is public; its API calls are what require a JWT.
                        .requestMatchers("/exam/**").permitAll()
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .requestMatchers("/recruiter/**").hasRole("RECRUITER")
                        // Candidates may reach their own report - ReportService.assertCanView
                        // enforces ownership and the recruiter's visibility flag either way.
                        .requestMatchers("/reports/**").hasAnyRole("ADMIN", "RECRUITER", "CANDIDATE")
                        // Interview detail view; a candidate must never reach it.
                        .requestMatchers("/interviews/**").hasAnyRole("ADMIN", "RECRUITER")
                        // Bulk scheduling is open to both staff roles; a candidate
                        // must never reach it.
                        .requestMatchers("/scheduling/**").hasAnyRole("ADMIN", "RECRUITER")
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginPage("/login")
                        .loginProcessingUrl("/login")
                        .defaultSuccessUrl("/", true)
                        .failureUrl("/login?error")
                        .permitAll())
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login?logout")
                        .permitAll());
        return http.build();
    }

    @Bean
    AuthenticationManager authenticationManager(AppUserDetailsService userDetailsService,
            PasswordEncoder encoder, ApplicationEventPublisher publisher) {

        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(encoder);

        ProviderManager manager = new ProviderManager(provider);
        // A manually constructed ProviderManager does not get Boot's event
        // publisher, so without this it stays silent and LoginAttemptListener
        // never hears about a failed login. Both chains authenticate through
        // this bean, which is what lets one listener cover both.
        manager.setAuthenticationEventPublisher(new DefaultAuthenticationEventPublisher(publisher));
        return manager;
    }

    private static void writeJsonError(jakarta.servlet.http.HttpServletResponse response,
            HttpStatus status, String message) throws java.io.IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"status\":%d,\"error\":\"%s\",\"message\":\"%s\"}"
                .formatted(status.value(), status.getReasonPhrase(), message));
    }
}
