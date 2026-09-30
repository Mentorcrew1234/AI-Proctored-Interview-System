package com.project.proctorinterview.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.project.proctorinterview.TestDatabaseCleaner;
import com.project.proctorinterview.common.Enums.Role;
import com.project.proctorinterview.passwordreset.PasswordResetService;
import com.project.proctorinterview.passwordreset.PasswordResetTokenRepository;
import com.project.proctorinterview.user.User;
import com.project.proctorinterview.user.UserRepository;

/**
 * Login throttling, password reset and the response security headers.
 *
 * <p>The rule under most scrutiny here is the one the rest of the codebase
 * already follows and these features could easily have broken: <b>nothing may
 * reveal whether an account exists</b>. A locked account has to fail like a
 * wrong password, and a reset request for an unknown address has to look exactly
 * like one for a real address.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthHardeningTest {

    @Autowired
    private TestDatabaseCleaner cleaner;
    @Autowired
    private MockMvc mvc;
    @Autowired
    private UserRepository users;
    @Autowired
    private PasswordResetTokenRepository resetTokens;
    @Autowired
    private PasswordResetService resets;
    @Autowired
    private LoginAttemptService attempts;
    @Autowired
    private PasswordEncoder encoder;

    private static final String EMAIL = "throttle@test.local";
    private static final String PASSWORD = "Correct@123";

    @BeforeEach
    void setUp() {
        cleaner.clean();
        attempts.reset();

        User user = new User();
        user.setEmail(EMAIL);
        user.setPasswordHash(encoder.encode(PASSWORD));
        user.setFullName("Throttle Test");
        user.setRole(Role.CANDIDATE);
        user.setEnabled(true);
        users.save(user);
    }

    private org.springframework.test.web.servlet.ResultActions apiLogin(String email, String password)
            throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)));
    }

    // ---- login throttling --------------------------------------------------

    @Test
    void theCorrectPasswordStillWorksBeforeTheLimit() throws Exception {
        apiLogin(EMAIL, "wrong").andExpect(status().isUnauthorized());
        apiLogin(EMAIL, "wrong").andExpect(status().isUnauthorized());

        apiLogin(EMAIL, PASSWORD).andExpect(status().isOk());
    }

    @Test
    void repeatedFailuresLockTheAccountEvenAgainstTheRightPassword() throws Exception {
        for (int i = 0; i < 5; i++) {
            apiLogin(EMAIL, "wrong").andExpect(status().isUnauthorized());
        }

        // The point of the whole feature: guessing stops working.
        apiLogin(EMAIL, PASSWORD).andExpect(status().isUnauthorized());
        assertThat(attempts.isBlocked(EMAIL)).isTrue();
        assertThat(attempts.blockedSeconds(EMAIL)).isPositive();
    }

    @Test
    void aLockedAccountFailsIdenticallyToAWrongPassword() throws Exception {
        for (int i = 0; i < 5; i++) {
            apiLogin(EMAIL, "wrong");
        }

        String locked = apiLogin(EMAIL, PASSWORD).andReturn().getResponse().getContentAsString();
        String unknown = apiLogin("nobody@test.local", "whatever")
                .andReturn().getResponse().getContentAsString();

        // Same status, same message. A different one would say "this account
        // exists and you found it", which is exactly what must not leak.
        assertThat(locked).contains("Invalid email or password");
        assertThat(unknown).contains("Invalid email or password");
    }

    @Test
    void aSuccessfulLoginClearsTheRun() throws Exception {
        apiLogin(EMAIL, "wrong");
        apiLogin(EMAIL, "wrong");
        apiLogin(EMAIL, PASSWORD).andExpect(status().isOk());

        // The counter restarted, so four more failures must not lock it.
        for (int i = 0; i < 4; i++) {
            apiLogin(EMAIL, "wrong");
        }
        assertThat(attempts.isBlocked(EMAIL)).isFalse();
    }

    @Test
    void failuresAgainstOneAccountDoNotAffectAnother() throws Exception {
        User other = new User();
        other.setEmail("other@test.local");
        other.setPasswordHash(encoder.encode("Other@12345"));
        other.setFullName("Other");
        other.setRole(Role.CANDIDATE);
        other.setEnabled(true);
        users.save(other);

        for (int i = 0; i < 6; i++) {
            apiLogin(EMAIL, "wrong");
        }

        apiLogin("other@test.local", "Other@12345").andExpect(status().isOk());
    }

    @Test
    void theThrottleAlsoCoversTheFormLogin() throws Exception {
        // Both chains authenticate through the same ProviderManager, which is
        // what lets one mechanism cover both. Worth pinning, because a second
        // implementation drifting from the first is the obvious way this breaks.
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/login").with(SecurityMockMvcRequestPostProcessors.csrf())
                    .param("username", EMAIL).param("password", "wrong"));
        }

        assertThat(attempts.isBlocked(EMAIL)).isTrue();
        apiLogin(EMAIL, PASSWORD).andExpect(status().isUnauthorized());
    }

    // ---- password reset ----------------------------------------------------

    @Test
    void aResetTokenSetsTheNewPasswordAndIsSpent() throws Exception {
        String token = resets.requestReset(EMAIL).orElseThrow();

        assertThat(resets.redeem(token, "Brand@New1")).isEqualTo(PasswordResetService.RedeemResult.OK);
        apiLogin(EMAIL, "Brand@New1").andExpect(status().isOk());

        // Single use.
        assertThat(resets.redeem(token, "Another@1"))
                .isEqualTo(PasswordResetService.RedeemResult.INVALID);
        apiLogin(EMAIL, "Another@1").andExpect(status().isUnauthorized());
    }

    @Test
    void theRawTokenIsNeverStored() {
        String token = resets.requestReset(EMAIL).orElseThrow();

        // Only the hash is on the row, so the table is not a source of working
        // links - the same reasoning as password_hash.
        assertThat(resetTokens.findAll()).hasSize(1);
        assertThat(resetTokens.findAll().getFirst().getTokenHash()).isNotEqualTo(token);
        assertThat(resetTokens.findByTokenHash(token)).isEmpty();
    }

    @Test
    void requestingAgainCancelsTheEarlierLink() {
        String first = resets.requestReset(EMAIL).orElseThrow();
        String second = resets.requestReset(EMAIL).orElseThrow();

        // A link that leaked earlier stops working the moment a newer one exists.
        assertThat(resets.isRedeemable(first)).isFalse();
        assertThat(resets.isRedeemable(second)).isTrue();
    }

    @Test
    void noTokenIsIssuedForAnUnknownOrDisabledAccount() {
        assertThat(resets.requestReset("nobody@test.local")).isEmpty();

        User user = users.findByEmailIgnoreCase(EMAIL).orElseThrow();
        user.setEnabled(false);
        users.save(user);

        // A disabled account is disabled for a reason; a reset must not be a
        // way around that.
        assertThat(resets.requestReset(EMAIL)).isEmpty();
    }

    @Test
    void theRequestPageSaysTheSameThingWhateverTheAddress() throws Exception {
        String real = mvc.perform(post("/forgot-password")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .param("email", EMAIL))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        String unknown = mvc.perform(post("/forgot-password")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .param("email", "nobody@test.local"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(real).contains("If that address belongs to an account");
        assertThat(unknown).isEqualTo(real);
    }

    @Test
    void redeemingClearsTheLockoutThePersonJustTriggered() throws Exception {
        for (int i = 0; i < 5; i++) {
            apiLogin(EMAIL, "wrong");
        }
        assertThat(attempts.isBlocked(EMAIL)).isTrue();

        String token = resets.requestReset(EMAIL).orElseThrow();
        resets.redeem(token, "Brand@New1");

        // Leaving them locked out of the password they have only now set would
        // be absurd.
        assertThat(attempts.isBlocked(EMAIL)).isFalse();
        apiLogin(EMAIL, "Brand@New1").andExpect(status().isOk());
    }

    @Test
    void bothResetPagesAreReachableWithoutSigningIn() throws Exception {
        // Necessarily so: someone who cannot log in must not be redirected to
        // the login page.
        mvc.perform(get("/forgot-password")).andExpect(status().isOk());
        mvc.perform(get("/reset-password").param("token", "nonsense"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "This link cannot be used")));
    }

    @Test
    void aMismatchedConfirmationDoesNotChangeThePassword() throws Exception {
        String token = resets.requestReset(EMAIL).orElseThrow();

        mvc.perform(post("/reset-password").with(SecurityMockMvcRequestPostProcessors.csrf())
                        .param("token", token)
                        .param("password", "Brand@New1")
                        .param("confirmPassword", "Different@1"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("do not match")));

        // Neither changed nor spent - the user can try again with the same link.
        apiLogin(EMAIL, PASSWORD).andExpect(status().isOk());
        assertThat(resets.isRedeemable(token)).isTrue();
    }

    @Test
    void aShortPasswordIsRefused() throws Exception {
        String token = resets.requestReset(EMAIL).orElseThrow();

        mvc.perform(post("/reset-password").with(SecurityMockMvcRequestPostProcessors.csrf())
                        .param("token", token)
                        .param("password", "short")
                        .param("confirmPassword", "short"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("at least 8")));

        assertThat(resets.isRedeemable(token)).isTrue();
    }

    @Test
    void aSuccessfulResetSendsThePersonToSignInAgain() throws Exception {
        String token = resets.requestReset(EMAIL).orElseThrow();

        mvc.perform(post("/reset-password").with(SecurityMockMvcRequestPostProcessors.csrf())
                        .param("token", token)
                        .param("password", "Brand@New1")
                        .param("confirmPassword", "Brand@New1"))
                .andExpect(redirectedUrl("/login?reset"));
    }

    @Test
    void resetPostsRequireACsrfToken() throws Exception {
        // These are on the session chain, so CSRF applies - the same rule the
        // rest of the non-/api POSTs follow.
        mvc.perform(post("/forgot-password").param("email", EMAIL))
                .andExpect(status().isForbidden());
    }

    // ---- security headers --------------------------------------------------

    @Test
    void everyPageCarriesTheSecurityHeaders() throws Exception {
        mvc.perform(get("/login"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
                .andExpect(header().exists("Content-Security-Policy"))
                .andExpect(header().exists("Permissions-Policy"));
    }

    @Test
    void theApiChainCarriesThemToo() throws Exception {
        apiLogin(EMAIL, "wrong")
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().exists("Content-Security-Policy"));
    }

    @Test
    void thePolicyPinsOriginsAndStillAllowsTheDetectorsToRun() throws Exception {
        String csp = mvc.perform(get("/login"))
                .andReturn().getResponse().getHeader("Content-Security-Policy");

        // Pinned: an injected script cannot fetch code from, or post data to,
        // somewhere else.
        assertThat(csp).contains("default-src 'self'");
        assertThat(csp).contains("connect-src 'self'");
        assertThat(csp).contains("object-src 'none'");
        assertThat(csp).contains("frame-ancestors 'self'");
        assertThat(csp).contains("form-action 'self'");

        // Allowed, because the interview does not work otherwise. Documented in
        // SecurityConfig rather than quietly assumed.
        assertThat(csp).contains("'wasm-unsafe-eval'");
        assertThat(csp).contains("blob:");
    }

    @Test
    void theCameraIsPermittedAndEverythingElseIsNot() throws Exception {
        String permissions = mvc.perform(get("/login"))
                .andReturn().getResponse().getHeader("Permissions-Policy");

        assertThat(permissions).contains("camera=(self)");
        assertThat(permissions).contains("microphone=(self)");
        assertThat(permissions).contains("geolocation=()");
    }
}
