package com.project.proctorinterview.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Outbound invitation email ({@code app.mail}).
 *
 * <p><b>Off by default.</b> With {@code enabled=false} nothing is ever sent and
 * no SMTP connection is attempted, so the system behaves exactly as it did
 * before email existed. That is deliberate: an interview must never depend on a
 * mail server being reachable, and the test suite must not need one.
 *
 * <p>{@code baseUrl} exists because an invite link has to be absolute to be
 * clickable in a mail client, and only the deployment knows its own public
 * address - the request that created the interview may have come from
 * {@code localhost} while candidates need {@code https://...}. A trailing slash
 * is tolerated and stripped rather than producing a double-slash link.
 *
 * <p>The SMTP credentials themselves are <b>not</b> here. They are Spring's own
 * {@code spring.mail.*} properties, supplied through the gitignored
 * {@code config/application.yml} or the environment, so no secret enters the
 * repository. See {@code docs/system/features/EMAIL-NOTIFICATIONS.md}.
 */
@ConfigurationProperties(prefix = "app.mail")
public record MailProperties(
        boolean enabled,
        String from,
        String fromName,
        String baseUrl) {

    private static final String DEFAULT_BASE_URL = "http://localhost:8080";
    private static final String DEFAULT_FROM_NAME = "Proctored Interviews";

    public MailProperties {
        fromName = blank(fromName) ? DEFAULT_FROM_NAME : fromName.trim();
        baseUrl = stripTrailingSlash(blank(baseUrl) ? DEFAULT_BASE_URL : baseUrl.trim());
        from = blank(from) ? null : from.trim();
    }

    /**
     * True only when sending is both switched on and actually configured.
     *
     * <p>Enabling without a from-address is a misconfiguration rather than an
     * instruction to send broken mail, so it is treated as off and logged by
     * the mail service instead of failing at startup - the same "fall back, do
     * not fail" rule the interview modes follow.
     */
    public boolean sendable() {
        return enabled && from != null;
    }

    /** Absolute, clickable invite link for one interview. */
    public String inviteLink(String inviteToken) {
        return baseUrl + "/exam/" + inviteToken;
    }

    /** Where a candidate signs in to see their own interviews. */
    public String dashboardLink() {
        return baseUrl + "/login";
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
