package com.project.proctorinterview.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.project.proctorinterview.config.LoginThrottleProperties;

/**
 * Slows down repeated failed logins for one account.
 *
 * <p>The system had no rate limiting and no lockout at all, so an unlimited
 * number of password guesses could be made against a known email address. Every
 * account in a bulk-scheduled batch has a generated password and a predictable
 * address, which makes that worth closing even at prototype scale.
 *
 * <h2>Design</h2>
 *
 * <b>In memory, on purpose</b>, like {@code AiHealthRegistry}. A restart clears
 * it and a second instance would count separately. That is a real limitation and
 * it is documented rather than hidden - but the alternative, a table written on
 * every failed login, is a schema and a write path for something that only has
 * to survive minutes.
 *
 * <b>Keyed by account, not by IP.</b> The threat here is guessing one person's
 * password, and an IP key is both easy to rotate and easy to share (a whole
 * campus behind one address). Keying by account means an attacker locks out the
 * account they are attacking - which is itself a denial of service, and the
 * reason the lock <b>expires on its own</b> after a short window rather than
 * needing an administrator.
 *
 * <b>It never reveals whether the account exists.</b> A locked account fails the
 * same way a wrong password does - `Invalid email or password` on the API, the
 * same error page on the form - matching the existing rule that unknown email
 * and wrong password are indistinguishable.
 *
 * <b>It cannot lock out a correct password indefinitely.</b> A success clears
 * the counter immediately, and the window is short.
 */
@Service
public class LoginAttemptService {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptService.class);

    private final LoginThrottleProperties properties;
    private final Map<String, Attempts> byAccount = new ConcurrentHashMap<>();

    public LoginAttemptService(LoginThrottleProperties properties) {
        this.properties = properties;
    }

    private record Attempts(int count, Instant firstFailure, Instant lockedUntil) {
    }

    /** True while this account is refusing attempts. */
    public boolean isBlocked(String email) {
        if (!properties.enabled() || email == null) {
            return false;
        }
        Attempts attempts = byAccount.get(key(email));
        if (attempts == null || attempts.lockedUntil() == null) {
            return false;
        }
        if (Instant.now().isAfter(attempts.lockedUntil())) {
            // Expired locks clean themselves up, so nothing has to sweep.
            byAccount.remove(key(email));
            return false;
        }
        return true;
    }

    /** Called on a failed login. Locks the account once the limit is reached. */
    public void recordFailure(String email) {
        if (!properties.enabled() || email == null) {
            return;
        }
        Instant now = Instant.now();

        byAccount.compute(key(email), (k, existing) -> {
            // A run of failures only counts as a run while it is recent. An
            // occasional typo weeks apart is not an attack and must not
            // accumulate into a lockout.
            boolean continuesRun = existing != null
                    && now.isBefore(existing.firstFailure().plus(window()));

            int count = continuesRun ? existing.count() + 1 : 1;
            Instant firstFailure = continuesRun ? existing.firstFailure() : now;
            Instant lockedUntil = count >= properties.maxAttempts()
                    ? now.plus(lockDuration())
                    : null;

            if (lockedUntil != null && (existing == null || existing.lockedUntil() == null)) {
                // Logged without the password and without saying whether the
                // account exists - this line is for whoever runs the server.
                log.warn("Login temporarily locked after {} failed attempts for account ending '{}'",
                        count, tail(email));
            }
            return new Attempts(count, firstFailure, lockedUntil);
        });
    }

    /** Called on a successful login - the run is over. */
    public void recordSuccess(String email) {
        if (email != null) {
            byAccount.remove(key(email));
        }
    }

    /** Seconds until this account accepts attempts again, or 0. */
    public long blockedSeconds(String email) {
        Attempts attempts = email == null ? null : byAccount.get(key(email));
        if (attempts == null || attempts.lockedUntil() == null) {
            return 0;
        }
        return Math.max(0, Duration.between(Instant.now(), attempts.lockedUntil()).toSeconds());
    }

    /** Test seam, and what a restart does for free. */
    public void reset() {
        byAccount.clear();
    }

    private Duration window() {
        return Duration.ofMinutes(properties.windowMinutes());
    }

    private Duration lockDuration() {
        return Duration.ofMinutes(properties.lockMinutes());
    }

    private static String key(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    /** Enough to identify a log line, not enough to disclose an address. */
    private static String tail(String email) {
        String trimmed = email.trim();
        return trimmed.length() <= 4 ? "****" : "***" + trimmed.substring(trimmed.length() - 4);
    }
}
