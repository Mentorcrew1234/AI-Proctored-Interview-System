package com.project.proctorinterview.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How hard the system pushes back on repeated failed logins.
 *
 * <p>Defaults are deliberately gentle. The goal is to make guessing a password
 * impractical, not to lock out someone who mistyped theirs three times: five
 * attempts, and the lock lifts itself after ten minutes without anyone having
 * to intervene.
 *
 * <p>{@code enabled: false} turns it off entirely, which exists for tests and
 * for a viva demonstration where an intentional lockout would be a nuisance.
 */
@ConfigurationProperties(prefix = "app.login-throttle")
public record LoginThrottleProperties(
        Boolean enabled,
        Integer maxAttempts,
        Integer windowMinutes,
        Integer lockMinutes) {

    public LoginThrottleProperties {
        enabled = enabled == null || enabled;
        maxAttempts = atLeast(maxAttempts, 5, 1);
        windowMinutes = atLeast(windowMinutes, 15, 1);
        lockMinutes = atLeast(lockMinutes, 10, 1);
    }

    /**
     * Falls back rather than failing startup, matching the rule used for the
     * interview mode: a nonsensical value should not stop the application, it
     * should be replaced with the sensible one and the application should run.
     */
    private static int atLeast(Integer value, int fallback, int minimum) {
        if (value == null || value < minimum) {
            return fallback;
        }
        return value;
    }
}
