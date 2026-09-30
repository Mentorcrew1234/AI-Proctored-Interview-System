package com.project.proctorinterview.ai;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.project.proctorinterview.ai.dto.AiDtos.AiOperation;
import com.project.proctorinterview.ai.dto.AiDtos.EvaluationRequest;
import com.project.proctorinterview.ai.dto.AiDtos.EvaluationResult;
import com.project.proctorinterview.ai.dto.AiDtos.GeneratedQuestion;
import com.project.proctorinterview.ai.dto.AiDtos.QuestionRequest;
import com.project.proctorinterview.common.Enums.EvaluatorType;
import com.project.proctorinterview.config.InterviewAiProperties;
import com.project.proctorinterview.common.Enums.QuestionSource;

/**
 * Chooses between the LLM and the offline fallback, per call.
 *
 * <p>The choice is made at call time rather than by configuration, because the
 * interesting failures happen mid-interview: the free-tier quota runs out after
 * the third answer, or the wifi drops. Falling back per call means the candidate
 * finishes their interview either way, and each record stores which path
 * produced it so nothing is misrepresented later.
 */
@Service
public class AiService {

    private static final Logger log = LoggerFactory.getLogger(AiService.class);

    private final GeminiLlmClient gemini;
    private final FallbackLlmClient fallback;
    private final InterviewAiProperties props;
    /**
     * Observability only. Nothing here changes which provider is chosen or what
     * it returns - it records what already happened, so that a provider outage
     * absorbed by the fallback is visible rather than silent.
     */
    private final AiHealthRegistry health;

    public AiService(GeminiLlmClient gemini, FallbackLlmClient fallback,
            InterviewAiProperties props, AiHealthRegistry health) {
        this.gemini = gemini;
        this.fallback = fallback;
        this.props = props;
        this.health = health;
    }

    /**
     * True when the real provider should be attempted at all.
     *
     * <p>In MOCK mode this is false, so no Gemini request is ever made - not
     * even one that would fail fast. That is the whole point: a failing call
     * still costs the configured timeout, which is felt as a delay between
     * questions.
     */
    private boolean geminiEnabled() {
        return !props.isMock() && gemini.isAvailable();
    }

    /** Exposed so the candidate UI can show a development-mode notice. */
    public boolean isMockMode() {
        return props.isMock();
    }

    public record QuestionSet(List<GeneratedQuestion> questions, QuestionSource source, String modelName) {
    }

    public record AnswerScore(EvaluationResult result, EvaluatorType evaluator, String modelName) {
    }

    /** True when a real LLM is configured AND selected. */
    public boolean llmConfigured() {
        return geminiEnabled();
    }

    /**
     * Runtime AI health for the admin page.
     *
     * <p>Assembled here because this is the only class that knows both the
     * configuration and which provider actually answered.
     */
    public com.project.proctorinterview.ai.dto.AiDtos.AiHealthView healthView() {
        return health.view(props.aiMode().name(), gemini.modelName(), gemini.isAvailable());
    }

    public QuestionSet generateQuestions(QuestionRequest request) {
        if (geminiEnabled()) {
            Instant startedAt = Instant.now();
            try {
                List<GeneratedQuestion> questions = gemini.generateQuestions(request);
                Duration took = Duration.between(startedAt, Instant.now());
                health.recordSuccess(AiOperation.QUESTION_GENERATION, gemini.modelName(), took);
                log.info("Generated {} question(s) via {} in {} ms",
                        questions.size(), gemini.modelName(), took.toMillis());
                return new QuestionSet(questions, QuestionSource.LLM, gemini.modelName());
            } catch (Exception e) {
                // The exception itself, not e.getMessage(): AiHealthRegistry needs
                // the real cause chain to tell a timeout apart from every other
                // failure - see AiHealthRegistry.classify.
                Duration took = Duration.between(startedAt, Instant.now());
                health.recordFailure(AiOperation.QUESTION_GENERATION, gemini.modelName(), took, e);
                // The duration matters as much as the message here: this is the
                // call ensureNextAdaptiveQuestion makes right after evaluate() in
                // the same request (Phase 6.6), so without it a slow POST
                // /answers can only be explained by guessing from adjacent log
                // timestamps - unreliable once requests from different
                // candidates interleave in the same log.
                log.warn("Gemini question generation failed after {} ms ({}); using the offline bank",
                        took.toMillis(), e.getMessage());
            }
        } else if (props.isMock()) {
            log.debug("MOCK interview mode: using the offline question bank, no Gemini call");
        } else {
            log.info("No Gemini API key configured; using the offline question bank");
        }

        List<GeneratedQuestion> questions = fallback.generateQuestions(request);
        health.recordFallback();
        return new QuestionSet(questions, QuestionSource.BANK, fallback.modelName());
    }

    public AnswerScore evaluate(EvaluationRequest request) {
        if (geminiEnabled()) {
            Instant startedAt = Instant.now();
            try {
                EvaluationResult result = gemini.evaluateAnswer(request);
                Duration took = Duration.between(startedAt, Instant.now());
                health.recordSuccess(AiOperation.ANSWER_EVALUATION, gemini.modelName(), took);
                // Evaluation used to log only on failure, so a working provider
                // was indistinguishable from one that was never called.
                log.info("Evaluated an answer via {} in {} ms", gemini.modelName(), took.toMillis());
                return new AnswerScore(result, EvaluatorType.LLM, gemini.modelName());
            } catch (Exception e) {
                Duration took = Duration.between(startedAt, Instant.now());
                health.recordFailure(AiOperation.ANSWER_EVALUATION, gemini.modelName(), took, e);
                log.warn("Gemini evaluation failed after {} ms ({}); scoring offline instead",
                        took.toMillis(), e.getMessage());
            }
        }
        EvaluationResult result = fallback.evaluateAnswer(request);
        health.recordFallback();
        return new AnswerScore(result, EvaluatorType.FALLBACK, fallback.modelName());
    }
}
