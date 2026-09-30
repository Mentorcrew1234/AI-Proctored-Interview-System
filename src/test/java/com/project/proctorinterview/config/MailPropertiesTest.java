package com.project.proctorinterview.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The mail configuration record.
 *
 * <p>Two rules are worth protecting here. First, email must be <b>off unless
 * explicitly turned on and actually configured</b> - the same "cannot happen by
 * accident" reasoning the interview modes follow, because the accident being
 * prevented is mailing real candidates from a development machine. Second, the
 * invite link must be absolute and well formed, since a broken link in an email
 * cannot be corrected after it is sent.
 */
class MailPropertiesTest {

    private static MailProperties props(boolean enabled, String from, String baseUrl) {
        return new MailProperties(enabled, from, null, baseUrl);
    }

    @Test
    void sendingIsOffWhenDisabled() {
        assertThat(props(false, "a@b.com", null).sendable()).isFalse();
    }

    /**
     * Enabling without a sender address is a misconfiguration, not an
     * instruction to send broken mail.
     */
    @Test
    void sendingIsOffWhenEnabledButNoSenderIsConfigured() {
        assertThat(props(true, null, null).sendable()).isFalse();
        assertThat(props(true, "", null).sendable()).isFalse();
        assertThat(props(true, "   ", null).sendable()).isFalse();
    }

    @Test
    void sendingIsOnOnlyWhenEnabledAndConfigured() {
        assertThat(props(true, "a@b.com", null).sendable()).isTrue();
    }

    @Test
    void aBlankBaseUrlFallsBackToLocalhostRatherThanProducingABrokenLink() {
        assertThat(props(true, "a@b.com", null).inviteLink("tok"))
                .isEqualTo("http://localhost:8080/exam/tok");
        assertThat(props(true, "a@b.com", "  ").inviteLink("tok"))
                .isEqualTo("http://localhost:8080/exam/tok");
    }

    /**
     * A trailing slash is the easy configuration mistake to make, and it would
     * otherwise produce {@code //exam/...}.
     */
    @Test
    void aTrailingSlashOnTheBaseUrlIsStripped() {
        assertThat(props(true, "a@b.com", "https://test.example.in/").inviteLink("tok"))
                .isEqualTo("https://test.example.in/exam/tok");
        assertThat(props(true, "a@b.com", "https://test.example.in").dashboardLink())
                .isEqualTo("https://test.example.in/login");
    }

    @Test
    void theSenderNameHasAReadableDefault() {
        assertThat(new MailProperties(true, "a@b.com", null, null).fromName())
                .isEqualTo("Proctored Interviews");
        assertThat(new MailProperties(true, "a@b.com", "Acme Hiring", null).fromName())
                .isEqualTo("Acme Hiring");
    }
}
