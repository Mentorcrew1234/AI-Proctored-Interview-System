package com.project.proctorinterview.ai;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

import com.project.proctorinterview.ai.dto.AiDtos.AiFailureCategory;
import com.project.proctorinterview.ai.dto.AiDtos.AiHealthView;
import com.project.proctorinterview.ai.dto.AiDtos.AiOperation;

/**
 * What the AI provider has actually been doing, in memory.
 *
 * <p>This exists because of a real incident. The configured Gemini model was
 * retired, every call returned 404, and the system carried on perfectly: the
 * fallback answered, interviews completed and reports were produced. That is
 * the fallback working exactly as designed - and it is also why a total
 * provider outage was invisible to anyone not reading the logs. This makes the
 * difference between "working" and "working on the fallback" observable.
 *
 * <p><b>In memory, and deliberately so.</b> This is operational state, not
 * interview data: it answers "is the LLM up right now", which stops being true
 * the moment the process restarts. Persisting it would mean a migration, a
 * table and rows nobody reads afterwards, to answer a question that is only
 * ever asked about the running instance. Restarting clears it, and a clustered
 * deployment would give each node its own view - both stated on the page rather
 * than hidden.
 *
 * <p><b>Nothing sensitive is stored here.</b> Failure text is truncated and run
 * through {@link #sanitise}, which strips the {@code key=} query parameter the
 * Gemini URL carries - an API key must never reach a screen or a log line
 * because a request happened to fail. A timeout doesn't even reach {@code
 * sanitise}: {@link #classify} recognises it from the exception's cause chain
 * and {@link #recordFailure} reports a synthesised message instead of the
 * provider's own, which for a timeout is actively misleading - see below.
 */
@Component
public class AiHealthRegistry {

    /** Long enough to identify a failure, short enough not to be a payload dump. */
    static final int MAX_REASON_LENGTH = 300;

    private final AtomicLong successCount = new AtomicLong();
    private final AtomicLong failureCount = new AtomicLong();
    private final AtomicLong fallbackCount = new AtomicLong();

    // Written from request threads, read from the admin page - so every field
    // that is read together is held in one immutable snapshot swapped under a
    // lock, rather than as separate volatiles that could be read half-updated.
    private final Object lock = new Object();
    private Snapshot lastSuccess;
    private Snapshot lastFailure;
    private Snapshot lastCall;

    private record Snapshot(AiOperation operation, String model, Instant at,
            long latencyMs, AiFailureCategory category, String reason) {
    }

    /**
     * A provider call that returned a usable result.
     *
     * @param latency measured around the provider call only
     */
    public void recordSuccess(AiOperation operation, String model, Duration latency) {
        successCount.incrementAndGet();
        Snapshot snapshot = new Snapshot(operation, model, Instant.now(), latency.toMillis(), null, null);
        synchronized (lock) {
            lastSuccess = snapshot;
            lastCall = snapshot;
        }
    }

    /**
     * A provider call that failed. The interview is unaffected - the caller has
     * already fallen back by the time this is recorded.
     *
     * <p>Takes the {@link Throwable} itself, not a pre-extracted message, because
     * {@link #classify} needs the real cause chain: a timed-out call surfaces at
     * the top as a generic {@code RestClientException} about response
     * extraction, and the {@link java.net.SocketTimeoutException} that actually
     * explains it is one or two levels down - a caller passing only
     * {@code e.getMessage()} would have already thrown that evidence away.
     */
    public void recordFailure(AiOperation operation, String model, Duration latency, Throwable failure) {
        failureCount.incrementAndGet();

        AiFailureCategory category = classify(failure);
        // A timeout's own message is the misleading one this exists to fix -
        // "Error while extracting response ... content type [octet-stream]"
        // explains nothing to a person reading the health page. The measured
        // latency is the honest, useful fact about what happened instead.
        String reason = category == AiFailureCategory.TIMEOUT
                ? "Timed out waiting for a response after " + latency.toMillis() + " ms."
                : sanitise(failure == null ? null : failure.getMessage());

        Snapshot snapshot = new Snapshot(operation, model, Instant.now(), latency.toMillis(),
                category, reason);
        synchronized (lock) {
            lastFailure = snapshot;
            lastCall = snapshot;
        }
    }

    /**
     * Whether a connect or read timeout is anywhere in this failure's cause
     * chain.
     *
     * <p>Walking the chain is not optional here - it was empirically confirmed
     * (a local server made to respond after the configured read timeout) that
     * Spring's {@code RestClient} does not surface a timeout as a
     * {@code SocketTimeoutException} at the top level. It surfaces as
     * {@code RestClientException: "Error while extracting response for type
     * [...] and content type [application/octet-stream]"}, with the real
     * {@link java.net.SocketTimeoutException} one or two levels into
     * {@code getCause()}. A plain {@code instanceof} on the caught exception
     * would have missed every real timeout.
     *
     * <p>Bounded at 20 hops purely as insurance against a pathological cyclic
     * cause chain; every real case observed here is 1-2 levels deep.
     */
    static AiFailureCategory classify(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 20; depth++) {
            if (current instanceof java.net.SocketTimeoutException) {
                return AiFailureCategory.TIMEOUT;
            }
            current = current.getCause();
        }
        return AiFailureCategory.OTHER;
    }

    /**
     * The offline provider answered instead of the LLM.
     *
     * <p>Counted separately from failures because the two are not the same
     * question: MOCK mode and an absent API key both fall back without anything
     * having gone wrong.
     */
    public void recordFallback() {
        fallbackCount.incrementAndGet();
    }

    /** Everything the admin page shows. Never contains a key or a payload. */
    public AiHealthView view(String aiMode, String configuredModel, boolean geminiConfigured) {
        Snapshot success;
        Snapshot failure;
        Snapshot last;
        synchronized (lock) {
            success = lastSuccess;
            failure = lastFailure;
            last = lastCall;
        }

        return new AiHealthView(
                aiMode,
                configuredModel,
                geminiConfigured,
                successCount.get(),
                failureCount.get(),
                fallbackCount.get(),
                success == null ? null : success.at(),
                failure == null ? null : failure.at(),
                last == null ? null : last.operation(),
                last == null ? null : last.model(),
                last == null ? null : last.latencyMs(),
                failure == null ? null : failure.category(),
                failure == null ? null : failure.reason());
    }

    /**
     * Makes a provider error safe to display.
     *
     * <p>The Gemini endpoint carries the API key as a {@code key=} query
     * parameter, so a transport error can quote a URL containing it. Redacting
     * here rather than at the call site means every path into this registry is
     * covered, including ones added later - a redaction that has to be
     * remembered is one that will eventually be forgotten.
     */
    static String sanitise(String reason) {
        if (reason == null || reason.isBlank()) {
            return "Unknown error";
        }
        // Any key=... run, whether in a URL, a quoted body or a bare message.
        String cleaned = reason.replaceAll("(?i)key=[^&\\s\"'}\\]]*", "key=REDACTED");
        // Bare Google API keys, in case one appears without its parameter name.
        cleaned = cleaned.replaceAll("AIza[0-9A-Za-z_\\-]{10,}", "REDACTED");
        cleaned = cleaned.replaceAll("\\s+", " ").trim();

        return cleaned.length() <= MAX_REASON_LENGTH
                ? cleaned
                : cleaned.substring(0, MAX_REASON_LENGTH) + "...";
    }
}
