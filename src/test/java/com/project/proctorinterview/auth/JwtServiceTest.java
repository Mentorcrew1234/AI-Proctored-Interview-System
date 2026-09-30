package com.project.proctorinterview.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.project.proctorinterview.common.Enums.Role;
import com.project.proctorinterview.config.JwtProperties;
import com.project.proctorinterview.user.User;

/** Token issuing and the four ways verification must fail closed. */
class JwtServiceTest {

    private static final String SECRET = "test-secret-that-is-long-enough-0123456789";

    private static JwtService service(long expiryMinutes) {
        return new JwtService(new JwtProperties(SECRET, expiryMinutes));
    }

    private static User user() {
        User u = new User();
        u.setId(42L);
        u.setEmail("someone@test.local");
        u.setFullName("Some One");
        u.setRole(Role.RECRUITER);
        u.setEnabled(true);
        return u;
    }

    @Test
    void issuedTokenCarriesIdAndRole() {
        JwtService jwt = service(60);

        var claims = jwt.parse(jwt.issue(user()));

        assertThat(claims).isNotNull();
        assertThat(jwt.userId(claims)).isEqualTo(42L);
        assertThat(jwt.role(claims)).isEqualTo(Role.RECRUITER);
        assertThat(claims.get("email", String.class)).isEqualTo("someone@test.local");
    }

    @Test
    void expiredTokenIsRejected() {
        // Negative expiry puts the expiration timestamp in the past.
        JwtService jwt = service(-1);

        assertThat(jwt.parse(jwt.issue(user()))).isNull();
    }

    @Test
    void tamperedPayloadIsRejected() {
        JwtService jwt = service(60);
        String token = jwt.issue(user());

        // Flip a character in the payload segment; the signature no longer matches.
        String[] parts = token.split("\\.");
        char[] payload = parts[1].toCharArray();
        payload[0] = payload[0] == 'A' ? 'B' : 'A';
        String tampered = parts[0] + "." + new String(payload) + "." + parts[2];

        assertThat(jwt.parse(tampered)).isNull();
    }

    @Test
    void tokenSignedWithAnotherSecretIsRejected() {
        String foreignToken = new JwtService(
                new JwtProperties("a-completely-different-secret-key-9876543210", 60))
                .issue(user());

        assertThat(service(60).parse(foreignToken)).isNull();
    }

    @Test
    void garbageAndEmptyTokensAreRejected() {
        JwtService jwt = service(60);

        assertThat(jwt.parse(null)).isNull();
        assertThat(jwt.parse("")).isNull();
        assertThat(jwt.parse("   ")).isNull();
        assertThat(jwt.parse("not-even-a-jwt")).isNull();
    }

    @Test
    void shortSecretIsRejectedAtStartup() {
        // Fail loudly at boot rather than silently signing with a weak key.
        assertThatThrownBy(() -> new JwtService(new JwtProperties("too-short", 60)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 characters");
    }
}
