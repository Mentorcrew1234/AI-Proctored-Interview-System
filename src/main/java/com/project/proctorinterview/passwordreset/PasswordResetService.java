package com.project.proctorinterview.passwordreset;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.project.proctorinterview.auth.LoginAttemptService;
import com.project.proctorinterview.config.PasswordResetProperties;
import com.project.proctorinterview.user.User;
import com.project.proctorinterview.user.UserRepository;

/**
 * Issues and redeems single-use password reset links.
 *
 * <p>Exists because there was previously no route back into an account whose
 * password was lost. Bulk scheduling makes that acute: it creates candidate
 * accounts with generated passwords that are shown once and never persisted, so
 * a candidate who loses the email had no way in at all.
 *
 * <h2>Rules</h2>
 *
 * <b>Requesting a reset never reveals whether an account exists.</b> The caller
 * gets the same answer either way. This is the rule the login already follows
 * (identical message for unknown email and wrong password) and the invite link
 * follows (404, not 403, for a stranger's link), applied here.
 *
 * <b>The raw token is never stored.</b> Only its SHA-256 is, so the database is
 * not a source of working reset links. A plain hash is right here where it would
 * be wrong for a password: the token is 256 bits of {@link SecureRandom}, so
 * there is no dictionary to attack and nothing for bcrypt's work factor to slow
 * down.
 *
 * <b>Single use, and newer supersedes older.</b> Requesting a reset spends every
 * pending token for that account, and redeeming one spends the rest, so a link
 * that leaked earlier stops working.
 *
 * <b>Redeeming clears the login throttle.</b> Someone resetting a password has
 * usually just failed to log in several times; leaving them locked out of the
 * password they have only now set would be absurd.
 */
@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);
    private static final int TOKEN_BYTES = 32;

    private final UserRepository users;
    private final PasswordResetTokenRepository tokens;
    private final PasswordEncoder encoder;
    private final LoginAttemptService loginAttempts;
    private final PasswordResetProperties properties;
    private final SecureRandom random = new SecureRandom();

    public PasswordResetService(UserRepository users, PasswordResetTokenRepository tokens,
            PasswordEncoder encoder, LoginAttemptService loginAttempts,
            PasswordResetProperties properties) {
        this.users = users;
        this.tokens = tokens;
        this.encoder = encoder;
        this.loginAttempts = loginAttempts;
        this.properties = properties;
    }

    /** Why a redemption attempt failed, for a message the user can act on. */
    public enum RedeemResult {
        OK,
        /** No such token, or it was already spent, or it has expired. */
        INVALID
    }

    /**
     * Creates a reset for this address if it belongs to an enabled account.
     *
     * @return the raw token to email, or empty when nothing should be sent -
     *         which the caller must treat as indistinguishable from success
     */
    @Transactional
    public Optional<String> requestReset(String email) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }

        User user = users.findByEmailIgnoreCase(email.trim()).orElse(null);
        if (user == null || !user.isEnabled()) {
            // Logged, but nothing is returned - a disabled account is disabled
            // for a reason, and a reset must not be a way around that.
            log.info("Password reset requested for an unknown or disabled address");
            return Optional.empty();
        }

        // A newer request supersedes any older pending one.
        tokens.invalidateAllFor(user.getId(), Instant.now());

        byte[] raw = new byte[TOKEN_BYTES];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

        PasswordResetToken entity = new PasswordResetToken();
        entity.setUser(user);
        entity.setTokenHash(hash(token));
        entity.setExpiresAt(Instant.now().plus(Duration.ofMinutes(properties.expiryMinutes())));
        tokens.save(entity);

        log.info("Issued a password reset for user {}, valid {} minutes",
                user.getId(), properties.expiryMinutes());
        return Optional.of(token);
    }

    /** True if this token could be redeemed right now - for rendering the form. */
    @Transactional(readOnly = true)
    public boolean isRedeemable(String token) {
        return findUsable(token).isPresent();
    }

    /**
     * Sets the new password and spends the token.
     *
     * <p>The token is re-checked here rather than trusted from the form render:
     * it can expire, or be superseded by a newer request, in between.
     */
    @Transactional
    public RedeemResult redeem(String token, String newPassword) {
        PasswordResetToken entity = findUsable(token).orElse(null);
        if (entity == null) {
            return RedeemResult.INVALID;
        }

        User user = entity.getUser();
        user.setPasswordHash(encoder.encode(newPassword));
        users.save(user);

        Instant now = Instant.now();
        entity.setUsedAt(now);
        tokens.save(entity);
        // Any other pending token for this account goes too.
        tokens.invalidateAllFor(user.getId(), now);

        // Someone resetting has usually just failed to log in several times.
        loginAttempts.recordSuccess(user.getEmail());

        log.info("Password reset completed for user {}", user.getId());
        return RedeemResult.OK;
    }

    private Optional<PasswordResetToken> findUsable(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return tokens.findByTokenHash(hash(token))
                .filter(t -> t.isUsableAt(Instant.now()));
    }

    /**
     * SHA-256, hex.
     *
     * <p>Deliberately not bcrypt. The token is 256 random bits, so there is
     * nothing to guess and no dictionary to slow down - and lookup is BY the
     * hash, which bcrypt's per-row salt would make impossible without scanning
     * every row.
     */
    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every JVM; this cannot happen.
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
