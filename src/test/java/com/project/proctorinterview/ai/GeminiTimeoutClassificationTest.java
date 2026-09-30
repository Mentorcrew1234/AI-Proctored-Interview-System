package com.project.proctorinterview.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.project.proctorinterview.ai.dto.AiDtos.AiFailureCategory;
import com.project.proctorinterview.ai.dto.AiDtos.EvaluationRequest;
import com.project.proctorinterview.ai.dto.AiDtos.QuestionRequest;
import com.project.proctorinterview.common.Enums.CandidateType;
import com.project.proctorinterview.common.Enums.Difficulty;
import com.project.proctorinterview.common.Enums.EvaluatorType;
import com.project.proctorinterview.common.Enums.InterviewLanguage;
import com.project.proctorinterview.common.Enums.InterviewType;
import com.project.proctorinterview.common.Enums.QuestionSource;
import com.project.proctorinterview.config.InterviewAiProperties;
import com.sun.net.httpserver.HttpServer;

/**
 * A real timeout, driven through the real stack: an actual slow socket, the
 * real {@link GeminiLlmClient} transport, the real {@link AiService} provider
 * selection, into the real {@link AiHealthRegistry}.
 *
 * <p>This is deliberately not a mocked-exception unit test. The whole point of
 * Phase 4.1 is that a timeout's <em>real</em> failure shape - confirmed here by
 * actually producing one - is a generic {@code RestClientException} about
 * response extraction, not a {@code SocketTimeoutException} at the top level.
 * A test built from an assumed exception shape could not have caught that; this
 * one reproduces the real shape every time it runs.
 *
 * <p>Fallback behaviour, provider-selection semantics and the 8 s timeout
 * itself are all untouched - this only proves that when a timeout does happen,
 * it is now reported honestly.
 */
class GeminiTimeoutClassificationTest {

    /** Looks like a real Google API key so a leak would be a visible test failure. */
    private static final String FAKE_KEY = "AIzaSyFAKEKEYFORTESTINGONLY0123456789";
    private static final Duration CLIENT_TIMEOUT = Duration.ofMillis(200);
    private static final Duration SERVER_DELAY = Duration.ofMillis(1200);

    private HttpServer slowServer;
    private AiService aiService;
    private AiHealthRegistry health;

    @BeforeEach
    void setUp() throws Exception {
        // A server that never answers within the client's timeout - the one
        // reliable way to produce a genuine read timeout rather than guess at
        // what exception it throws.
        slowServer = HttpServer.create(new InetSocketAddress(0), 0);
        slowServer.createContext("/", exchange -> {
            try {
                Thread.sleep(SERVER_DELAY.toMillis());
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            byte[] body = "{}".getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        slowServer.start();
        int port = slowServer.getAddress().getPort();

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CLIENT_TIMEOUT);
        factory.setReadTimeout(CLIENT_TIMEOUT);
        RestClient restClient = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .requestFactory(factory)
                .build();

        // A real, deliberately fake key - present so a leak into the health
        // view would be a visible, obvious test failure.
        var props = new com.project.proctorinterview.config.GeminiProperties(
                FAKE_KEY, "gemini-3.1-flash-lite", "http://localhost:" + port, 1);
        GeminiLlmClient gemini = new GeminiLlmClient(props, restClient);

        FallbackLlmClient fallback = new FallbackLlmClient();
        fallback.loadBank();

        health = new AiHealthRegistry();
        aiService = new AiService(gemini, fallback,
                new InterviewAiProperties(InterviewAiProperties.AiMode.GEMINI), health);
    }

    @AfterEach
    void tearDown() {
        slowServer.stop(0);
    }

    // ---- question generation --------------------------------------------

    @Test
    void aRealReadTimeoutDuringGenerationIsRecordedAsTimeoutNotAParsingError() {
        var result = aiService.generateQuestions(
                new QuestionRequest("Java", InterviewType.TECHNICAL, CandidateType.FRESHER,
                        null, 5, InterviewLanguage.ENGLISH));

        // Fallback behaviour is completely unaffected: the interview still
        // gets its questions, from the bank, exactly as before this change.
        assertThat(result.source()).isEqualTo(QuestionSource.BANK);
        assertThat(result.questions()).isNotEmpty();

        var view = health.view("GEMINI", "gemini-3.1-flash-lite", true);
        assertThat(view.failureCount()).isEqualTo(1);
        assertThat(view.fallbackCount()).isEqualTo(1);
        assertThat(view.lastFailureCategory()).isEqualTo(AiFailureCategory.TIMEOUT);

        String reason = view.lastFailureReason();
        // Not the old, misleading message.
        assertThat(reason).doesNotContain("content type");
        assertThat(reason).doesNotContain("octet-stream");
        assertThat(reason).doesNotContain("extracting response");
        // The honest replacement: how long it actually waited before giving up.
        assertThat(reason).contains("Timed out");
        assertThat(reason).contains("ms");

        // The API key must never appear, however the failure was produced.
        assertThat(reason).doesNotContain(FAKE_KEY);
    }

    // ---- answer evaluation -------------------------------------------------

    @Test
    void aRealReadTimeoutDuringEvaluationIsRecordedAsTimeoutAndStillScoresOffline() {
        var score = aiService.evaluate(new EvaluationRequest(
                "How would you debug a slow endpoint?",
                java.util.List.of("check the database", "profile the request"),
                "I would check the database queries and profile the request.",
                "Java", InterviewType.TECHNICAL, Difficulty.MEDIUM));

        // The candidate still gets a score - from the fallback scorer.
        assertThat(score.evaluator()).isEqualTo(EvaluatorType.FALLBACK);

        var view = health.view("GEMINI", "gemini-3.1-flash-lite", true);
        assertThat(view.failureCount()).isEqualTo(1);
        assertThat(view.lastFailureCategory()).isEqualTo(AiFailureCategory.TIMEOUT);
        assertThat(view.lastOperation().name()).isEqualTo("ANSWER_EVALUATION");
        assertThat(view.lastFailureReason()).doesNotContain(FAKE_KEY);
    }

    // ---- a call that succeeds within the budget is not misclassified -------

    /**
     * The negative case, proven on the same real transport: a call that
     * finishes comfortably inside the timeout must be recorded as a genuine
     * success, not merely "not yet failed".
     */
    @Test
    void aCallThatFinishesWithinTheTimeoutIsRecordedAsASuccess() throws Exception {
        slowServer.stop(0);
        HttpServer fastServer = HttpServer.create(new InetSocketAddress(0), 0);
        fastServer.createContext("/", exchange -> {
            // The inner array is what GeminiLlmClient.extractText hands to the
            // question parser; it has to arrive as an escaped JSON *string*
            // value, because that's the shape of a real candidates/content/parts
            // envelope - the model's output is text, not nested JSON.
            String innerJson = "[{\\\"text\\\":\\\"A scenario question\\\","
                    + "\\\"difficulty\\\":\\\"EASY\\\","
                    + "\\\"expectedPoints\\\":[\\\"a point\\\"]}]";
            byte[] body = ("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\""
                    + innerJson + "\"}]}}]}").getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        fastServer.start();
        try {
            int port = fastServer.getAddress().getPort();
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(CLIENT_TIMEOUT);
            factory.setReadTimeout(CLIENT_TIMEOUT);
            RestClient restClient = RestClient.builder()
                    .baseUrl("http://localhost:" + port)
                    .requestFactory(factory)
                    .build();
            var props = new com.project.proctorinterview.config.GeminiProperties(
                    FAKE_KEY, "gemini-3.1-flash-lite", "http://localhost:" + port, 1);
            GeminiLlmClient fastGemini = new GeminiLlmClient(props, restClient);
            FallbackLlmClient fallback = new FallbackLlmClient();
            fallback.loadBank();
            AiHealthRegistry freshHealth = new AiHealthRegistry();
            AiService fastAiService = new AiService(fastGemini, fallback,
                    new InterviewAiProperties(InterviewAiProperties.AiMode.GEMINI), freshHealth);

            var result = fastAiService.generateQuestions(
                    new QuestionRequest("Java", InterviewType.TECHNICAL, CandidateType.FRESHER,
                            null, 1, InterviewLanguage.ENGLISH));

            assertThat(result.source()).isEqualTo(QuestionSource.LLM);
            var view = freshHealth.view("GEMINI", "gemini-3.1-flash-lite", true);
            assertThat(view.successCount()).isEqualTo(1);
            assertThat(view.failureCount()).isZero();
            assertThat(view.lastFailureCategory()).isNull();
        } finally {
            fastServer.stop(0);
        }
    }
}
