package com.project.proctorinterview.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How long a password reset link stays usable.
 *
 * <p>Thirty minutes: long enough to survive a slow mail server and someone
 * checking their phone, short enough that a link left in an inbox is not a
 * standing key to the account. A newer request supersedes an older one
 * regardless, so the window is not the only protection.
 *
 * <p>Note there is no {@code enabled} flag. The flow is always available; what
 * decides whether a link can actually reach anyone is {@code app.mail.enabled},
 * and {@code PasswordResetPageController} says so plainly when mail is off
 * rather than pretending an email was sent.
 */
@ConfigurationProperties(prefix = "app.password-reset")
public record PasswordResetProperties(Integer expiryMinutes) {

    public PasswordResetProperties {
        // Falls back rather than failing startup, matching the interview mode.
        if (expiryMinutes == null || expiryMinutes < 1 || expiryMinutes > 1440) {
            expiryMinutes = 30;
        }
    }
}
