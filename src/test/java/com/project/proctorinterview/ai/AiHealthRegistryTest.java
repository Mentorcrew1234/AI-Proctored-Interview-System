package com.project.proctorinterview.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.project.proctorinterview.ai.dto.AiDtos.AiFailureCategory;
import com.project.proctorinterview.ai.dto.AiDtos.AiHealthView;
import com.project.proctorinterview.ai.dto.AiDtos.AiOperation;

/**
 * The AI health registry.
 *
 * <p>The requirement this exists for is narrow and important: a provider
 * outage must be visible, and nothing sensitive may become visible with it.
 * The redaction tests are the ones that matter most - the Gemini endpoint
 * carries its API key in the query string, so an error message is the most
 * likely way a key ever reaches a screen.
 */
class AiHealthRegistryTest {

    private static final String MODEL = "gemini-3.1-flash-lite";
    private final AiHealthRegistry registry = new AiHealthRegistry();

    private AiHealthView view() {
        return registry.view("GEMINI", MODEL, true);
    }

    // ---- starting state -----------------------------------------------------

    @Test
    void startsEmptyAndSaysSoRatherThanClaimingHealth() {
        AiHealthView v = view();

        assertThat(v.successCount()).isZero();
        assertThat(v.failureCount()).isZero();
        assertThat(v.fallbackCount()).isZero();
        assertThat(v.hasActivity()).isFalse();
        // Nulls mean "not yet on this instance", which is not the same as zero.
        assertThat(v.lastSuccessAt()).isNull();
        assertThat(v.lastFailureAt()).isNull();
        assertThat(v.lastOperation()).isNull();
        assertThat(v.lastLatencyMs()).isNull();
        assertThat(v.providerState()).isEqualTo("Configured, not yet used");
    }

    // ---- recording ----------------------------------------------------------

    @Test
    void recordsASuccessfulCallWithItsOperationModelAndLatency() {
        registry.recordSuccess(AiOperation.QUESTION_GENERATION, MODEL, Duration.ofMillis(2478));

        AiHealthView v = view();
        assertThat(v.successCount()).isEqualTo(1);
        assertThat(v.failureCount()).isZero();
        assertThat(v.lastSuccessAt()).isNotNull();
        assertThat(v.lastOperation()).isEqualTo(AiOperation.QUESTION_GENERATION);
        assertThat(v.lastModel()).isEqualTo(MODEL);
        assertThat(v.lastLatencyMs()).isEqualTo(2478);
        assertThat(v.providerState()).isEqualTo("Healthy");
    }

    @Test
    void recordsAFailureWithItsReason() {
        registry.recordFailure(AiOperation.ANSWER_EVALUATION, MODEL, Duration.ofMillis(410),
                new RuntimeException("404 Not Found: model is no longer available"));

        AiHealthView v = view();
        assertThat(v.failureCount()).isEqualTo(1);
        assertThat(v.lastFailureAt()).isNotNull();
        assertThat(v.lastFailureReason()).contains("no longer available");
        assertThat(v.lastOperation()).isEqualTo(AiOperation.ANSWER_EVALUATION);
    }

    @Test
    void countsFallbacksSeparatelyFromFailures() {
        // MOCK mode and a missing key both fall back without anything failing,
        // so the two counters answer different questions.
        registry.recordFallback();
        registry.recordFallback();

        AiHealthView v = view();
        assertThat(v.fallbackCount()).isEqualTo(2);
        assertThat(v.failureCount()).isZero();
        assertThat(v.hasActivity()).isTrue();
    }

    @Test
    void aLaterSuccessDoesNotEraseTheLastFailure() {
        registry.recordFailure(AiOperation.QUESTION_GENERATION, MODEL, Duration.ofMillis(50),
                new RuntimeException("429 quota"));
        registry.recordSuccess(AiOperation.QUESTION_GENERATION, MODEL, Duration.ofMillis(2000));

        AiHealthView v = view();
        // Both are kept: "it works now" and "it failed earlier" are both worth
        // knowing, and a recovered provider that keeps flapping would otherwise
        // look permanently healthy.
        assertThat(v.lastSuccessAt()).isNotNull();
        assertThat(v.lastFailureAt()).isNotNull();
        assertThat(v.lastFailureReason()).contains("429");
        assertThat(v.providerState()).isEqualTo("Degraded - some calls fell back");
    }

    // ---- the redaction requirement -----------------------------------------

    /**
     * The reason a failure is the most dangerous place for a key: the Gemini
     * URL carries {@code ?key=...}, and transport errors quote the URL.
     */
    @Test
    void anApiKeyInAFailureUrlNeverReachesTheHealthView() {
        String secret = "AIzaSyD-ThisLooksExactlyLikeARealGoogleKey123456";
        registry.recordFailure(AiOperation.QUESTION_GENERATION, MODEL, Duration.ofMillis(120),
                new RuntimeException("I/O error on POST request for "
                        + "\"https://generativelanguage.googleapis.com/v1beta/models/x:generateContent"
                        + "?key=" + secret + "\": Connection reset"));

        String reason = view().lastFailureReason();
        assertThat(reason).doesNotContain(secret);
        assertThat(reason).doesNotContain("AIzaSy");
        assertThat(reason).contains("key=REDACTED");
        // Still useful: the operator can see what actually went wrong.
        assertThat(reason).contains("Connection reset");
    }

    @Test
    void abareApiKeyWithoutItsParameterNameIsAlsoRedacted() {
        String secret = "AIzaSyABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        registry.recordFailure(AiOperation.ANSWER_EVALUATION, MODEL, Duration.ofMillis(80),
                new RuntimeException("401 Unauthorized: the key " + secret + " is not valid"));

        assertThat(view().lastFailureReason()).doesNotContain(secret).contains("REDACTED");
    }

    @Test
    void aVeryLongProviderErrorIsTruncatedRatherThanDumped() {
        registry.recordFailure(AiOperation.QUESTION_GENERATION, MODEL, Duration.ofMillis(90),
                new RuntimeException("x".repeat(5000)));

        String reason = view().lastFailureReason();
        assertThat(reason.length()).isLessThanOrEqualTo(AiHealthRegistry.MAX_REASON_LENGTH + 3);
        assertThat(reason).endsWith("...");
    }

    @Test
    void anEmptyOrNullReasonStillProducesSomethingReadable() {
        registry.recordFailure(AiOperation.QUESTION_GENERATION, MODEL, Duration.ZERO, (Throwable) null);
        assertThat(view().lastFailureReason()).isEqualTo("Unknown error");

        registry.recordFailure(AiOperation.QUESTION_GENERATION, MODEL, Duration.ZERO,
                new RuntimeException("   "));
        assertThat(view().lastFailureReason()).isEqualTo("Unknown error");
    }

    // ---- provider state reporting -------------------------------------------

    @Test
    void reportsConfigurationRatherThanFailureWhenGeminiIsSimplyNotSelected() {
        assertThat(registry.view("MOCK", MODEL, true).providerState())
                .isEqualTo("MOCK mode - offline fallback only, Gemini never called");
        assertThat(registry.view("GEMINI", MODEL, false).providerState())
                .isEqualTo("Not configured - offline fallback only");
    }

    @Test
    void reportsFailingWhenEveryAttemptHasFallenBack() {
        registry.recordFailure(AiOperation.QUESTION_GENERATION, MODEL, Duration.ofMillis(10),
                new RuntimeException("404"));
        registry.recordFallback();

        assertThat(view().providerState()).isEqualTo("Failing - every attempt fell back");
    }

    // ---- concurrency ---------------------------------------------------------

    /** Answers are evaluated on request threads, so counting must be safe. */
    @Test
    void countersAreSafeUnderConcurrentCalls() throws Exception {
        int threads = 8;
        int perThread = 250;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                start.await();
                for (int i = 0; i < perThread; i++) {
                    registry.recordSuccess(AiOperation.ANSWER_EVALUATION, MODEL, Duration.ofMillis(5));
                    registry.recordFailure(AiOperation.QUESTION_GENERATION, MODEL,
                            Duration.ofMillis(5), new RuntimeException("boom"));
                    registry.recordFallback();
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        AiHealthView v = view();
        assertThat(v.successCount()).isEqualTo((long) threads * perThread);
        assertThat(v.failureCount()).isEqualTo((long) threads * perThread);
        assertThat(v.fallbackCount()).isEqualTo((long) threads * perThread);
    }

    @Test
    void everyOperationHasAReadableLabel() {
        assertThat(List.of(AiOperation.values())).allSatisfy(
                op -> assertThat(op.label()).isNotBlank());
    }

    // ---- timeout classification -----------------------------------------
    //
    // The reason this matters: a real Gemini call that times out does not
    // surface as a SocketTimeoutException at the top level. It surfaces as
    // RestClientException("Error while extracting response for type [...]
    // and content type [application/octet-stream]"), with the actual
    // SocketTimeoutException one or two levels into getCause() - confirmed by
    // driving GeminiLlmClient against a local server that responds after the
    // configured read timeout (see GeminiTimeoutClassificationTest). These
    // tests pin the classifier against that exact, real shape rather than a
    // guess at it.

    @Test
    void aFailureWhoseCauseChainContainsASocketTimeoutIsClassifiedAsTimeout() {
        // The real shape observed from RestClient: a generic top-level
        // message wrapping the actual SocketTimeoutException.
        Exception timeout = new org.springframework.web.client.RestClientException(
                "Error while extracting response for type [java.lang.String] "
                        + "and content type [application/octet-stream]",
                new java.net.SocketTimeoutException("Read timed out"));

        registry.recordFailure(AiOperation.QUESTION_GENERATION, MODEL, Duration.ofMillis(8006), timeout);

        AiHealthView v = view();
        assertThat(v.lastFailureCategory()).isEqualTo(AiFailureCategory.TIMEOUT);
        // The confusing original message must not be what the page shows.
        assertThat(v.lastFailureReason()).doesNotContain("content type");
        assertThat(v.lastFailureReason()).doesNotContain("octet-stream");
        assertThat(v.lastFailureReason()).doesNotContain("extracting response");
        // What it shows instead is honest and specific: how long it waited.
        assertThat(v.lastFailureReason()).contains("8006 ms");
    }

    /** The timeout can be nested deeper than one level - the chain must be walked, not peeked at. */
    @Test
    void aSocketTimeoutNestedSeveralLevelsDeepIsStillClassifiedAsTimeout() {
        Exception buried = new RuntimeException("outer wrapper",
                new RuntimeException("middle wrapper",
                        new java.net.SocketTimeoutException("connect timed out")));

        registry.recordFailure(AiOperation.ANSWER_EVALUATION, MODEL, Duration.ofMillis(8000), buried);

        assertThat(view().lastFailureCategory()).isEqualTo(AiFailureCategory.TIMEOUT);
    }

    /**
     * A timeout's own message never reaches the page - not even the sanitised
     * version of it. If it happened to quote a key, that must not matter,
     * because it is never read in the first place.
     */
    @Test
    void aTimeoutsOwnMessageIsNeverUsedEvenIfItWouldHaveNeededRedaction() {
        Exception timeout = new RuntimeException(
                "would have contained ?key=AIzaSyDanger12345 if anyone looked at it",
                new java.net.SocketTimeoutException("Read timed out"));

        registry.recordFailure(AiOperation.QUESTION_GENERATION, MODEL, Duration.ofMillis(500), timeout);

        String reason = view().lastFailureReason();
        assertThat(reason).doesNotContain("AIzaSyDanger12345");
        assertThat(reason).doesNotContain("would have contained");
    }

    /** Connection refused is a real, distinct failure - it must not be mistaken for a timeout. */
    @Test
    void aConnectionRefusedIsNotMisclassifiedAsATimeout() {
        Exception refused = new org.springframework.web.client.ResourceAccessException(
                "I/O error on POST request for \"http://localhost:1/x\": Connection refused: getsockopt",
                new java.net.ConnectException("Connection refused: getsockopt"));

        registry.recordFailure(AiOperation.QUESTION_GENERATION, MODEL, Duration.ofMillis(15), refused);

        AiHealthView v = view();
        assertThat(v.lastFailureCategory()).isEqualTo(AiFailureCategory.OTHER);
        assertThat(v.lastFailureReason()).contains("Connection refused");
    }

    /** An ordinary HTTP error response is not a timeout either. */
    @Test
    void anHttpErrorResponseIsNotMisclassifiedAsATimeout() {
        Exception notFound = org.springframework.web.client.HttpClientErrorException.create(
                org.springframework.http.HttpStatus.NOT_FOUND, "Not Found",
                org.springframework.http.HttpHeaders.EMPTY, new byte[0], null);

        registry.recordFailure(AiOperation.QUESTION_GENERATION, MODEL, Duration.ofMillis(300), notFound);

        assertThat(view().lastFailureCategory()).isEqualTo(AiFailureCategory.OTHER);
    }

    @Test
    void aNullFailureIsClassifiedAsOtherRatherThanThrowing() {
        registry.recordFailure(AiOperation.QUESTION_GENERATION, MODEL, Duration.ZERO, (Throwable) null);

        AiHealthView v = view();
        assertThat(v.lastFailureCategory()).isEqualTo(AiFailureCategory.OTHER);
        assertThat(v.lastFailureReason()).isEqualTo("Unknown error");
    }

    @Test
    void everyFailureCategoryHasAReadableLabel() {
        assertThat(List.of(AiFailureCategory.values())).allSatisfy(
                c -> assertThat(c.label()).isNotBlank());
    }
}
