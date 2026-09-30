package com.project.proctorinterview.ai.dto;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import com.project.proctorinterview.common.Enums.CandidateType;
import com.project.proctorinterview.common.Enums.Difficulty;
import com.project.proctorinterview.common.Enums.InterviewLanguage;
import com.project.proctorinterview.common.Enums.InterviewType;

/** Inputs and outputs of the AI layer, independent of any provider. */
public final class AiDtos {

    private AiDtos() {
    }

    /** Which AI operation a health record refers to. */
    public enum AiOperation {
        QUESTION_GENERATION,
        ANSWER_EVALUATION;

        public String label() {
            return switch (this) {
                case QUESTION_GENERATION -> "Question generation";
                case ANSWER_EVALUATION -> "Answer evaluation";
            };
        }
    }

    /**
     * Why a provider call failed, at the coarseness the health page can act on.
     *
     * <p>Two values only. {@code TIMEOUT} exists because the transport's actual
     * failure signature for a timed-out call - {@code RestClientException:
     * "Error while extracting response for type [...] and content type
     * [application/octet-stream]"} with a {@link java.net.SocketTimeoutException}
     * buried in its cause chain - reads like a parsing bug, not what it is. See
     * {@link com.project.proctorinterview.ai.AiHealthRegistry#classify}.
     */
    public enum AiFailureCategory {
        TIMEOUT,
        OTHER;

        public String label() {
            return switch (this) {
                case TIMEOUT -> "Timed out";
                case OTHER -> "Other error";
            };
        }
    }

    /**
     * Runtime AI health, as shown to an administrator.
     *
     * <p>Every field is either configuration or an observation of a call this
     * instance made. It carries no API key, no request URL and no provider
     * payload - {@code lastFailureReason} is already truncated and redacted by
     * {@link com.project.proctorinterview.ai.AiHealthRegistry#sanitise}.
     *
     * <p>Nulls are meaningful: they mean "has not happened on this instance
     * yet", which is different from zero.
     */
    public record AiHealthView(
            String aiMode,
            String configuredModel,
            boolean geminiConfigured,
            long successCount,
            long failureCount,
            long fallbackCount,
            Instant lastSuccessAt,
            Instant lastFailureAt,
            AiOperation lastOperation,
            String lastModel,
            Long lastLatencyMs,
            /** Null when there has been no failure yet on this instance. */
            AiFailureCategory lastFailureCategory,
            String lastFailureReason) {

        /** True once the provider has actually been exercised on this instance. */
        public boolean hasActivity() {
            return successCount > 0 || failureCount > 0 || fallbackCount > 0;
        }

        /**
         * Whether the LLM is currently the one answering.
         *
         * <p>Deliberately reports "using the offline fallback" rather than
         * "down" when Gemini is simply not selected: MOCK mode and a missing
         * key are configuration, not failure.
         */
        public String providerState() {
            if (!geminiConfigured) {
                return "Not configured - offline fallback only";
            }
            if ("MOCK".equalsIgnoreCase(aiMode)) {
                return "MOCK mode - offline fallback only, Gemini never called";
            }
            if (successCount == 0 && failureCount > 0) {
                return "Failing - every attempt fell back";
            }
            if (failureCount > 0) {
                return "Degraded - some calls fell back";
            }
            return successCount > 0 ? "Healthy" : "Configured, not yet used";
        }
    }

    /**
     * @param language           the interview's configured language. Carried so
     *                           the AI layer knows what was asked for rather than
     *                           assuming; see {@link #resolvedLanguage()} for what
     *                           actually happens when a value has no prompt
     *                           support.
     * @param avoidQuestionTexts recent question texts from other interviews with
     *                           this same context, most recent first. A bounded
     *                           hint for the prompt to avoid repeating - never
     *                           enforced by parsing, and empty for a provider (or
     *                           test) that has no use for it. See
     *                           {@code QuestionRepository.findRecentTextsByContext}
     *                           for how this is bounded before it ever reaches here.
     * @param priorAnswer        the previous question's difficulty and the
     *                           candidate's Java-computed overall score on it,
     *                           for adaptive generation (Phase 6.2) to pick an
     *                           appropriate next depth. Null for the first
     *                           question of an interview, and always null on the
     *                           fixed-question path - see
     *                           {@code QuestionService.ensureQuestions}, which
     *                           never sets it.
     */
    public record QuestionRequest(
            String domain,
            InterviewType interviewType,
            CandidateType candidateType,
            Integer experienceYears,
            int count,
            InterviewLanguage language,
            List<String> avoidQuestionTexts,
            PriorAnswerContext priorAnswer) {

        public QuestionRequest {
            avoidQuestionTexts = avoidQuestionTexts == null ? List.of() : List.copyOf(avoidQuestionTexts);
        }

        /** The shape every caller used before avoidance context existed. */
        public QuestionRequest(String domain, InterviewType interviewType, CandidateType candidateType,
                Integer experienceYears, int count, InterviewLanguage language) {
            this(domain, interviewType, candidateType, experienceYears, count, language, List.of(), null);
        }

        /** The shape every caller used before adaptive context existed (Phase 5.2). */
        public QuestionRequest(String domain, InterviewType interviewType, CandidateType candidateType,
                Integer experienceYears, int count, InterviewLanguage language,
                List<String> avoidQuestionTexts) {
            this(domain, interviewType, candidateType, experienceYears, count, language,
                    avoidQuestionTexts, null);
        }

        /**
         * Languages the question prompt can actually produce.
         *
         * <p>Exactly one today. This is deliberately a separate list from the
         * {@link InterviewLanguage} enum: the enum says what may be
         * <em>scheduled</em>, this says what the AI layer can honestly
         * <em>deliver</em>, and a new enum value must be added here
         * consciously rather than silently inheriting support it does not have.
         */
        private static final Set<InterviewLanguage> SUPPORTED = EnumSet.of(InterviewLanguage.ENGLISH);

        /** The language the prompt will be written in. Never null. */
        public InterviewLanguage resolvedLanguage() {
            return language != null && SUPPORTED.contains(language)
                    ? language
                    : InterviewLanguage.ENGLISH;
        }

        /**
         * True when the requested language is not one the prompt supports and
         * English is being used instead. The caller logs this rather than
         * failing the interview.
         */
        public boolean languageFellBack() {
            return language != null && !SUPPORTED.contains(language);
        }
    }

    /**
     * The minimum signal adaptive generation needs from the previous question:
     * how hard it was, and how well the candidate actually did on it, as Java's
     * scoring already computed - never the transcript, sub-scores, feedback,
     * strengths or weaknesses. The overall score is already the single
     * authoritative "how did they do" number this system produces; sending more
     * would bloat the prompt without adding a signal Java hasn't already
     * distilled into that number.
     *
     * @param previousDifficulty  the difficulty of the question just answered
     * @param previousOverallScore the Java-computed weighted overall score
     *                              (0-100) for that answer
     */
    public record PriorAnswerContext(Difficulty previousDifficulty, int previousOverallScore) {
    }

    /**
     * @param expectedPoints what a good answer should cover. Doubles as the
     *                       rubric for the offline fallback scorer.
     */
    public record GeneratedQuestion(
            String text,
            Difficulty difficulty,
            List<String> expectedPoints) {
    }

    public record EvaluationRequest(
            String questionText,
            List<String> expectedPoints,
            String cleanTranscript,
            String domain,
            InterviewType interviewType,
            Difficulty difficulty) {
    }

    /**
     * Four sub-scores, each 0-100. There is deliberately no overall score here:
     * it is computed in Java from configured weights so the model never decides
     * the outcome.
     */
    public record EvaluationResult(
            int technicalScore,
            int relevanceScore,
            int problemSolvingScore,
            int communicationScore,
            String feedback,
            List<String> strengths,
            List<String> weaknesses) {

        public EvaluationResult {
            technicalScore = clamp(technicalScore);
            relevanceScore = clamp(relevanceScore);
            problemSolvingScore = clamp(problemSolvingScore);
            communicationScore = clamp(communicationScore);
            strengths = strengths == null ? List.of() : List.copyOf(strengths);
            weaknesses = weaknesses == null ? List.of() : List.copyOf(weaknesses);
        }

        /** A model can return anything; scores are forced into range on the way in. */
        private static int clamp(int score) {
            return Math.max(0, Math.min(100, score));
        }
    }
}
