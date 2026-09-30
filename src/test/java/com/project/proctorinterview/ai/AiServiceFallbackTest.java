package com.project.proctorinterview.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import com.project.proctorinterview.ai.dto.AiDtos.AiOperation;
import com.project.proctorinterview.ai.dto.AiDtos.EvaluationRequest;
import com.project.proctorinterview.ai.dto.AiDtos.EvaluationResult;
import com.project.proctorinterview.ai.dto.AiDtos.GeneratedQuestion;
import com.project.proctorinterview.ai.dto.AiDtos.QuestionRequest;
import com.project.proctorinterview.common.Enums.CandidateType;
import com.project.proctorinterview.common.Enums.Difficulty;
import com.project.proctorinterview.common.Enums.EvaluatorType;
import com.project.proctorinterview.common.Enums.InterviewLanguage;
import com.project.proctorinterview.common.Enums.InterviewType;
import com.project.proctorinterview.common.Enums.QuestionSource;
import com.project.proctorinterview.config.InterviewAiProperties;

import org.springframework.http.HttpStatus;

/**
 * The guarantee this whole layer exists for: a candidate mid-interview must
 * never be stranded because the LLM failed.
 *
 * <p>Quota exhaustion after the third answer, a dropped connection, or no API
 * key at all must all degrade to offline scoring rather than an error, and the
 * record must say honestly which path graded it.
 */
class AiServiceFallbackTest {

    private GeminiLlmClient gemini;
    private FallbackLlmClient fallback;
    private AiService aiService;
    private AiHealthRegistry health;

    @BeforeEach
    void setUp() {
        gemini = mock(GeminiLlmClient.class);
        fallback = new FallbackLlmClient();
        fallback.loadBank();
        // GEMINI mode: the real provider is attempted, with fallback on failure.
        health = new AiHealthRegistry();
        aiService = new AiService(gemini, fallback,
                new InterviewAiProperties(InterviewAiProperties.AiMode.GEMINI), health);
    }

    private static QuestionRequest questionRequest() {
        return new QuestionRequest("Java", InterviewType.TECHNICAL, CandidateType.FRESHER, null, 5, InterviewLanguage.ENGLISH);
    }

    private static EvaluationRequest evaluationRequest(String answer) {
        return new EvaluationRequest("How would you avoid a double booking?",
                List.of("race condition on a shared resource", "database transaction"),
                answer, "Java", InterviewType.TECHNICAL, Difficulty.MEDIUM);
    }

    // ---- no key configured --------------------------------------------------

    @Test
    void withoutAnApiKeyEverythingUsesTheOfflineBankWithoutCallingGemini() {
        when(gemini.isAvailable()).thenReturn(false);

        var questions = aiService.generateQuestions(questionRequest());

        assertThat(questions.source()).isEqualTo(QuestionSource.BANK);
        assertThat(questions.questions()).hasSize(5);
        verify(gemini, never()).generateQuestions(any());
        assertThat(aiService.llmConfigured()).isFalse();
    }

    // ---- runtime failures ---------------------------------------------------

    @Test
    void quotaExhaustionFallsBackInsteadOfFailing() {
        when(gemini.isAvailable()).thenReturn(true);
        when(gemini.generateQuestions(any()))
                .thenThrow(HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS,
                        "Too Many Requests", null, null, null));

        var questions = aiService.generateQuestions(questionRequest());

        assertThat(questions.source()).isEqualTo(QuestionSource.BANK);
        assertThat(questions.questions()).hasSize(5);
    }

    @Test
    void aDroppedConnectionMidInterviewStillProducesAnEvaluation() {
        when(gemini.isAvailable()).thenReturn(true);
        when(gemini.evaluateAnswer(any()))
                .thenThrow(new ResourceAccessException("Connection reset"));

        var score = aiService.evaluate(evaluationRequest(
                "I would use a database transaction to handle the race condition"));

        assertThat(score.evaluator()).isEqualTo(EvaluatorType.FALLBACK);
        assertThat(score.modelName()).isEqualTo("offline-fallback");
        assertThat(score.result().technicalScore()).isPositive();
    }

    @Test
    void malformedModelOutputFallsBackRatherThanScoringZero() {
        when(gemini.isAvailable()).thenReturn(true);
        when(gemini.evaluateAnswer(any()))
                .thenThrow(new IllegalStateException("Gemini returned empty text"));

        var score = aiService.evaluate(evaluationRequest(
                "I would use a database transaction to handle the race condition"));

        assertThat(score.evaluator()).isEqualTo(EvaluatorType.FALLBACK);
        // A parse failure must not be recorded as a bad answer.
        assertThat(score.result().technicalScore()).isPositive();
    }

    @Test
    void aMidInterviewFailureOnlyAffectsThatAnswer() {
        when(gemini.isAvailable()).thenReturn(true);
        var good = new EvaluationResult(80, 80, 80, 80, "ok", List.of(), List.of());
        when(gemini.evaluateAnswer(any()))
                .thenReturn(good)
                .thenThrow(new ResourceAccessException("quota"))
                .thenReturn(good);
        when(gemini.modelName()).thenReturn("gemini-2.5-flash");

        var first = aiService.evaluate(evaluationRequest("answer one"));
        var second = aiService.evaluate(evaluationRequest("answer two"));
        var third = aiService.evaluate(evaluationRequest("answer three"));

        // The service retries the real provider on the next call rather than
        // giving up on it for the rest of the interview.
        assertThat(first.evaluator()).isEqualTo(EvaluatorType.LLM);
        assertThat(second.evaluator()).isEqualTo(EvaluatorType.FALLBACK);
        assertThat(third.evaluator()).isEqualTo(EvaluatorType.LLM);
    }

    // ---- successful path is recorded as such --------------------------------

    @Test
    void aWorkingLlmIsRecordedAsTheEvaluator() {
        when(gemini.isAvailable()).thenReturn(true);
        when(gemini.modelName()).thenReturn("gemini-2.5-flash");
        when(gemini.generateQuestions(any())).thenReturn(List.of(
                new GeneratedQuestion("A generated question?", Difficulty.MEDIUM, List.of("point"))));
        when(gemini.evaluateAnswer(any()))
                .thenReturn(new EvaluationResult(90, 88, 85, 82, "Strong.", List.of("clear"), List.of()));

        var questions = aiService.generateQuestions(questionRequest());
        var score = aiService.evaluate(evaluationRequest("a good answer"));

        assertThat(questions.source()).isEqualTo(QuestionSource.LLM);
        assertThat(questions.modelName()).isEqualTo("gemini-2.5-flash");
        assertThat(score.evaluator()).isEqualTo(EvaluatorType.LLM);
        assertThat(score.result().technicalScore()).isEqualTo(90);
    }

    // ---- MOCK mode ----------------------------------------------------------

    @Test
    void mockModeNeverCallsGeminiEvenWhenAKeyIsConfigured() {
        // The point of MOCK is removing external latency: a call that would fail
        // still costs the configured timeout, which the candidate waits through.
        AiService mock = new AiService(gemini, fallback,
                new InterviewAiProperties(InterviewAiProperties.AiMode.MOCK), new AiHealthRegistry());
        when(gemini.isAvailable()).thenReturn(true);

        var questions = mock.generateQuestions(questionRequest());
        var score = mock.evaluate(evaluationRequest("a reasonable answer about transactions"));

        assertThat(questions.source()).isEqualTo(QuestionSource.BANK);
        assertThat(score.evaluator()).isEqualTo(EvaluatorType.FALLBACK);
        assertThat(mock.isMockMode()).isTrue();
        assertThat(mock.llmConfigured()).isFalse();

        verify(gemini, never()).generateQuestions(any());
        verify(gemini, never()).evaluateAnswer(any());
    }

    @Test
    void mockModeStillProducesAUsableInterview() {
        AiService mock = new AiService(gemini, fallback,
                new InterviewAiProperties(InterviewAiProperties.AiMode.MOCK), new AiHealthRegistry());

        var questions = mock.generateQuestions(questionRequest());
        assertThat(questions.questions()).hasSize(5);
        assertThat(questions.questions()).allSatisfy(q -> assertThat(q.text()).isNotBlank());

        // Answers are still scored, so the report pipeline stays compatible.
        var score = mock.evaluate(evaluationRequest(
                "I would use a database transaction to handle the race condition"));
        assertThat(score.result().technicalScore()).isPositive();
    }

    @Test
    void geminiModeIsUnchanged() {
        when(gemini.isAvailable()).thenReturn(true);
        when(gemini.modelName()).thenReturn("gemini-2.5-flash");
        when(gemini.evaluateAnswer(any()))
                .thenReturn(new EvaluationResult(90, 88, 85, 82, "Strong.", List.of(), List.of()));

        AiService live = new AiService(gemini, fallback,
                new InterviewAiProperties(InterviewAiProperties.AiMode.GEMINI), new AiHealthRegistry());

        assertThat(live.isMockMode()).isFalse();
        assertThat(live.evaluate(evaluationRequest("good")).evaluator())
                .isEqualTo(EvaluatorType.LLM);
    }

    // ---- the offline scorer itself ------------------------------------------

    @Test
    void theOfflineScorerRewardsCoveringTheExpectedPoints() {
        var covered = fallback.evaluateAnswer(evaluationRequest(
                "This is a race condition on a shared resource, so I would wrap it in a "
                        + "database transaction with locking to make it safe."));
        var vague = fallback.evaluateAnswer(evaluationRequest(
                "I would just try to make it work somehow."));

        assertThat(covered.relevanceScore()).isGreaterThan(vague.relevanceScore());
    }

    @Test
    void anEmptyAnswerScoresZeroAndSaysSo() {
        var result = fallback.evaluateAnswer(evaluationRequest("   "));

        assertThat(result.technicalScore()).isZero();
        assertThat(result.relevanceScore()).isZero();
        assertThat(result.weaknesses()).contains("No answer given");
    }

    @Test
    void theOfflineScorerAdmitsWhatItIs() {
        var result = fallback.evaluateAnswer(evaluationRequest("a reasonable answer about transactions"));

        // The candidate and recruiter should not be told a language model read this.
        assertThat(result.feedback()).contains("without a language model");
    }

    @Test
    void theOfflineBankNeverAsksDefinitionQuestions() {
        var questions = fallback.generateQuestions(questionRequest());

        assertThat(questions).allSatisfy(q -> {
            String text = q.text().toLowerCase();
            assertThat(text).doesNotStartWith("what is");
            assertThat(text).doesNotStartWith("define");
            assertThat(text).doesNotContain("explain the difference between");
        });
    }

    @Test
    void anUnknownDomainStillYieldsQuestions() {
        var questions = fallback.generateQuestions(new QuestionRequest(
                "Quantum Basket Weaving", InterviewType.TECHNICAL, CandidateType.FRESHER, null, 5, InterviewLanguage.ENGLISH));

        assertThat(questions).hasSize(5);
    }

    /**
     * This used to pass only because selection wrapped with a modulo, so an
     * 8-question interview drawn from a 5-question pool repeated the first
     * three. The interview is still filled, but now with distinct questions -
     * see FallbackQuestionSelectionTest for the full selection rules.
     */
    @Test
    void askingForMoreQuestionsThanOneDifficultyBandHoldsStillFillsTheInterview() {
        var questions = fallback.generateQuestions(new QuestionRequest(
                "Java", InterviewType.TECHNICAL, CandidateType.FRESHER, null, 8, InterviewLanguage.ENGLISH));

        assertThat(questions).hasSize(8);
        assertThat(questions).extracting(GeneratedQuestion::text).doesNotHaveDuplicates();
    }

    @Test
    void hrInterviewsUseTheDomainIndependentSet() {
        var questions = fallback.generateQuestions(new QuestionRequest(
                "Java", InterviewType.HR_GENERAL, CandidateType.EXPERIENCED, 3, 5, InterviewLanguage.ENGLISH));

        assertThat(questions).hasSize(5);
        assertThat(questions.getFirst().text()).isNotBlank();
    }

    // ---- health recording (observability only) ------------------------------
    //
    // These assert what the registry saw, never that provider selection or
    // scoring changed - the registry must be a pure observer.

    @Test
    void aSuccessfulGeminiCallIsRecordedAsHealthy() {
        when(gemini.isAvailable()).thenReturn(true);
        when(gemini.modelName()).thenReturn("gemini-3.1-flash-lite");
        when(gemini.generateQuestions(any())).thenReturn(List.of(
                new GeneratedQuestion("A scenario question", Difficulty.EASY, List.of("a point"))));

        aiService.generateQuestions(questionRequest());

        var view = health.view("GEMINI", "gemini-3.1-flash-lite", true);
        assertThat(view.successCount()).isEqualTo(1);
        assertThat(view.failureCount()).isZero();
        assertThat(view.lastOperation()).isEqualTo(AiOperation.QUESTION_GENERATION);
        assertThat(view.lastSuccessAt()).isNotNull();
        assertThat(view.providerState()).isEqualTo("Healthy");
    }

    @Test
    void aFailedGeminiCallIsRecordedAsAFailureAndAFallback() {
        when(gemini.isAvailable()).thenReturn(true);
        when(gemini.modelName()).thenReturn("gemini-3.1-flash-lite");
        when(gemini.generateQuestions(any()))
                .thenThrow(new IllegalStateException("404 model not found"));

        // The interview still gets its questions - that is the existing contract.
        var set = aiService.generateQuestions(questionRequest());
        assertThat(set.source()).isEqualTo(QuestionSource.BANK);

        var view = health.view("GEMINI", "gemini-3.1-flash-lite", true);
        assertThat(view.failureCount()).isEqualTo(1);
        assertThat(view.fallbackCount()).isEqualTo(1);
        assertThat(view.lastFailureReason()).contains("404");
    }

    @Test
    void aSuccessfulEvaluationIsRecordedSeparatelyFromGeneration() {
        when(gemini.isAvailable()).thenReturn(true);
        when(gemini.modelName()).thenReturn("gemini-3.1-flash-lite");
        when(gemini.evaluateAnswer(any())).thenReturn(
                new EvaluationResult(80, 80, 80, 80, "good", List.of("clear"), List.of()));

        aiService.evaluate(evaluationRequest("A clear, structured answer about indexing."));

        var view = health.view("GEMINI", "gemini-3.1-flash-lite", true);
        assertThat(view.successCount()).isEqualTo(1);
        assertThat(view.lastOperation()).isEqualTo(AiOperation.ANSWER_EVALUATION);
    }

    @Test
    void mockModeRecordsFallbackWithoutRecordingAFailure() {
        AiService mock = new AiService(gemini, fallback,
                new InterviewAiProperties(InterviewAiProperties.AiMode.MOCK), health);
        when(gemini.isAvailable()).thenReturn(true);

        mock.generateQuestions(questionRequest());

        var view = health.view("MOCK", "gemini-3.1-flash-lite", true);
        // Nothing failed: MOCK simply never calls the provider.
        assertThat(view.failureCount()).isZero();
        assertThat(view.fallbackCount()).isEqualTo(1);
        assertThat(view.providerState()).contains("MOCK mode");
    }

    /**
     * The propagation itself: whatever the interview was scheduled with is what
     * the provider is asked for. Captured at the boundary rather than asserted
     * on generated text, which would be brittle.
     */
    @Test
    void theRequestedLanguageReachesTheProvider() {
        when(gemini.isAvailable()).thenReturn(true);
        when(gemini.modelName()).thenReturn("gemini-3.1-flash-lite");
        when(gemini.generateQuestions(any())).thenReturn(List.of(
                new GeneratedQuestion("A scenario question", Difficulty.EASY, List.of("a point"))));

        var captor = org.mockito.ArgumentCaptor.forClass(QuestionRequest.class);
        aiService.generateQuestions(new QuestionRequest("Java", InterviewType.TECHNICAL,
                CandidateType.FRESHER, null, 5, InterviewLanguage.ENGLISH));

        verify(gemini).generateQuestions(captor.capture());
        assertThat(captor.getValue().language()).isEqualTo(InterviewLanguage.ENGLISH);
        assertThat(captor.getValue().resolvedLanguage()).isEqualTo(InterviewLanguage.ENGLISH);
    }

    /** The offline bank must accept the same request shape. */
    @Test
    void theFallbackAcceptsALanguageAwareRequest() {
        when(gemini.isAvailable()).thenReturn(false);

        var set = aiService.generateQuestions(new QuestionRequest("Java", InterviewType.TECHNICAL,
                CandidateType.FRESHER, null, 5, InterviewLanguage.ENGLISH));

        assertThat(set.source()).isEqualTo(QuestionSource.BANK);
        assertThat(set.questions()).hasSize(5);
    }
}
