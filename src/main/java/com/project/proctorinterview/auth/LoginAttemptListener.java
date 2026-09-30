package com.project.proctorinterview.auth;

import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Component;

/**
 * Feeds login outcomes to {@link LoginAttemptService}.
 *
 * <p>Listening to Spring Security's own events, rather than recording at each
 * call site, is what keeps the throttle from having two implementations: the
 * form login and {@code POST /api/auth/login} both authenticate through the same
 * {@code ProviderManager}, so both publish here.
 *
 * <p>{@code SecurityConfig} has to give that ProviderManager an event publisher
 * for any of this to fire - a manually constructed one does not get Boot's
 * automatically.
 */
@Component
public class LoginAttemptListener {

    private final LoginAttemptService attempts;

    public LoginAttemptListener(LoginAttemptService attempts) {
        this.attempts = attempts;
    }

    /**
     * Every failure type counts, not just a bad password.
     *
     * <p>{@code AbstractAuthenticationFailureEvent} covers bad credentials, an
     * unknown account and a disabled one alike. Counting only bad passwords
     * would leave an attacker free to probe addresses at full speed to find out
     * which ones exist, which is the enumeration this codebase already refuses
     * to allow through its error messages.
     *
     * <p>A locked account's own failure is included, which is harmless: it is
     * already locked, and the lock's expiry is measured from when it was set.
     */
    @EventListener
    public void onFailure(AbstractAuthenticationFailureEvent event) {
        attempts.recordFailure(name(event.getAuthentication()));
    }

    @EventListener
    public void onSuccess(AuthenticationSuccessEvent event) {
        attempts.recordSuccess(name(event.getAuthentication()));
    }

    private static String name(org.springframework.security.core.Authentication authentication) {
        return authentication == null ? null : authentication.getName();
    }
}
