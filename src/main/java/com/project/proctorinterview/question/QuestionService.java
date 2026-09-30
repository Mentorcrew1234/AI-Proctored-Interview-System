package com.project.proctorinterview.question;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.project.proctorinterview.ai.AiService;
import com.project.proctorinterview.ai.FallbackLlmClient;
import com.project.proctorinterview.ai.dto.AiDtos.GeneratedQuestion;
import com.project.proctorinterview.ai.dto.AiDtos.PriorAnswerContext;
import com.project.proctorinterview.ai.dto.AiDtos.QuestionRequest;
import com.project.proctorinterview.answer.Answer;
import com.project.proctorinterview.answer.AnswerRepository;
import com.project.proctorinterview.common.Enums.QuestionSource;
import com.project.proctorinterview.interview.Interview;
import com.project.proctorinterview.interview.InterviewSession;

/**
 * Generates and stores the question set for a session.
 *
 * <p>Two independent paths, selected by {@code app.interview.question-mode}:
 *
 * <ul>
 *   <li>{@link #ensureQuestions} - the existing, fully verified behaviour.
 *       Generated once, at the start, and then fixed: the candidate always sees
 *       the same questions in the same order even if they reload, and a failed
 *       LLM call cannot change the interview halfway through. Unchanged by
 *       Phase 6.2 in every respect.</li>
 *   <li>{@link #ensureNextAdaptiveQuestion} - Phase 6.2's backend foundation.
 *       One question at a time, informed by the previous answer's performance.
 *       Not yet called from anywhere - no controller wires it up, and no
 *       config makes {@code ADAPTIVE} the default.</li>
 * </ul>
 */
@Service
@Transactional
public class QuestionService {

    private static final Logger log = LoggerFactory.getLogger(QuestionService.class);

    /**
     * How many previous question texts to offer Gemini as "avoid repeating
     * these". Small on purpose: this is a prompt hint, not a corpus - a longer
     * list only grows the prompt without meaningfully improving variety.
     */
    private static final int MAX_AVOID_HISTORY = 8;

    private final QuestionRepository questions;
    private final AiService ai;
    private final AnswerRepository answers;
    private final FallbackLlmClient fallback;
    private final AdaptiveQuestionWriter adaptiveWriter;

    public QuestionService(QuestionRepository questions, AiService ai, AnswerRepository answers,
            FallbackLlmClient fallback, AdaptiveQuestionWriter adaptiveWriter) {
        this.questions = questions;
        this.ai = ai;
        this.answers = answers;
        this.fallback = fallback;
        this.adaptiveWriter = adaptiveWriter;
    }

    /**
     * Creates the questions for a session if it does not already have them.
     *
     * <p>Unchanged by Phase 6.2. This is the {@code FIXED} question-mode path,
     * and remains the only path taken regardless of configuration - Phase 6.2
     * is backend foundation only and wires nothing new into it.
     *
     * @return the ordered question list, whether just created or already present
     */
    public List<Question> ensureQuestions(InterviewSession session) {
        List<Question> existing = questions.findBySessionIdOrderBySequenceNoAsc(session.getId());
        if (!existing.isEmpty()) {
            return existing;
        }

        Interview interview = session.getInterview();

        // Only worth the query when Gemini will actually be attempted - MOCK
        // mode and an unconfigured key always use the fallback, which has no
        // use for this list, matching the existing shape of geminiEnabled().
        List<String> avoidTexts = ai.llmConfigured()
                ? questions.findRecentTextsByContext(
                        interview.getDomain(), interview.getInterviewType(),
                        interview.getCandidateType(), interview.getLanguage(),
                        PageRequest.of(0, MAX_AVOID_HISTORY))
                : List.of();

        QuestionRequest request = new QuestionRequest(
                interview.getDomain(),
                interview.getInterviewType(),
                interview.getCandidateType(),
                interview.getExperienceYears(),
                interview.getQuestionCount(),
                interview.getLanguage(),
                avoidTexts);

        // An unsupported language falls back to English rather than failing the
        // interview - the same rule INTERVIEW_MODE follows for an unrecognised
        // value. It is logged because a candidate silently interviewed in the
        // wrong language would be a far worse outcome than a noisy log.
        if (request.languageFellBack()) {
            log.warn("Interview {} requests {}, which the question prompt does not support; "
                    + "generating in {} instead",
                    interview.getId(), interview.getLanguage(), request.resolvedLanguage());
        }

        AiService.QuestionSet generated = ai.generateQuestions(request);

        List<Question> saved = new ArrayList<>();
        int sequence = 1;
        for (GeneratedQuestion item : generated.questions()) {
            if (sequence > interview.getQuestionCount()) {
                break;
            }
            Question question = new Question();
            question.setSession(session);
            question.setSequenceNo(sequence++);
            question.setText(item.text());
            question.setDifficulty(item.difficulty());
            question.setExpectedPoints(item.expectedPoints());
            question.setSource(generated.source());
            question.setModelName(generated.modelName());
            saved.add(questions.save(question));
        }
        return saved;
    }

    @Transactional(readOnly = true)
    public List<Question> forSession(Long sessionId) {
        return questions.findBySessionIdOrderBySequenceNoAsc(sessionId);
    }

    // ---- adaptive (Phase 6.2 backend foundation) -----------------------------

    /**
     * Generates and persists the next question for one session, adapted from
     * the previous question's difficulty and the candidate's Java-computed
     * overall score on it.
     *
     * <p>Backend foundation only: nothing calls this yet. It is not reachable
     * from {@link #ensureQuestions}, from any controller, or from any config
     * default - {@code app.interview.question-mode} defaults to {@code FIXED},
     * which never touches this method.
     *
     * @return the newly generated question; the existing one if a concurrent
     *         call already won the race for this sequence number; or
     *         {@code null} if this session already has every question the
     *         interview calls for, or if the most recently generated question
     *         has not been answered yet (there is nothing new to adapt from).
     */
    public Question ensureNextAdaptiveQuestion(InterviewSession session) {
        Interview interview = session.getInterview();
        List<Question> existing = questions.findBySessionIdOrderBySequenceNoAsc(session.getId());

        if (existing.size() >= interview.getQuestionCount()) {
            return null;
        }

        PriorAnswerContext priorAnswer = null;
        if (!existing.isEmpty()) {
            Question previous = existing.getLast();
            Answer previousAnswer = answers.findByQuestionId(previous.getId()).orElse(null);
            if (previousAnswer == null || previousAnswer.getOverallScore() == null) {
                // The question this one would adapt from has not been scored
                // yet - "question 1 -> answered -> evaluated -> generate
                // question 2" has not reached that step, so there is nothing
                // to generate yet, not a next question to hand back.
                return null;
            }
            priorAnswer = new PriorAnswerContext(previous.getDifficulty(), previousAnswer.getOverallScore());
        }

        int nextSequence = existing.size() + 1;

        // Only worth the query when Gemini will actually be attempted - same
        // rule ensureQuestions already follows. This naturally already covers
        // this session's own prior questions: the query matches on interview
        // context (domain/type/candidate-type/language), not on "a different
        // session", so once question 1 is persisted it is already eligible to
        // be returned here for question 2 without any change to that query.
        List<String> avoidTexts = ai.llmConfigured()
                ? questions.findRecentTextsByContext(
                        interview.getDomain(), interview.getInterviewType(),
                        interview.getCandidateType(), interview.getLanguage(),
                        PageRequest.of(0, MAX_AVOID_HISTORY))
                : List.of();

        // count=1: one question is what is actually needed, and Gemini already
        // has the avoid list plus (when present) the adaptive signal below to
        // reason from - unlike ensureQuestions, there is no "vary difficulty
        // across the set" batch to fill.
        QuestionRequest request = new QuestionRequest(
                interview.getDomain(),
                interview.getInterviewType(),
                interview.getCandidateType(),
                interview.getExperienceYears(),
                1,
                interview.getLanguage(),
                avoidTexts,
                priorAnswer);

        if (request.languageFellBack()) {
            log.warn("Interview {} requests {}, which the question prompt does not support; "
                    + "generating in {} instead",
                    interview.getId(), interview.getLanguage(), request.resolvedLanguage());
        }

        AiService.QuestionSet generated = ai.generateQuestions(request);
        GeneratedQuestion chosen = generated.questions().getFirst();

        // FallbackLlmClient holds no state and never sees the avoid list (it is
        // not modified by this phase), so called repeatedly with count=1 within
        // one interview it would otherwise hand back the same first-tier entry
        // every time. Gemini already receives the avoid list in its prompt, so
        // this only needs to handle the BANK path, and only when it actually
        // collides.
        if (generated.source() == QuestionSource.BANK && isDuplicateInSession(chosen, existing)) {
            chosen = firstUnusedFallbackCandidate(interview, nextSequence, existing);
        }

        // Final guard regardless of path: never persist a duplicate silently.
        // In practice this should not fire - Gemini is instructed to avoid the
        // history, and the branch above already resolves the one case where
        // the fallback provider could otherwise repeat itself - but a stored
        // duplicate would be a worse outcome than a loud failure here.
        if (isDuplicateInSession(chosen, existing)) {
            throw new IllegalStateException(
                    "No unused question is available for session " + session.getId()
                            + " at sequence " + nextSequence);
        }

        Question question = new Question();
        question.setSession(session);
        question.setSequenceNo(nextSequence);
        question.setText(chosen.text());
        question.setDifficulty(chosen.difficulty());
        question.setExpectedPoints(chosen.expectedPoints());
        question.setSource(generated.source());
        question.setModelName(generated.modelName());

        try {
            return adaptiveWriter.insert(question);
        } catch (DataIntegrityViolationException e) {
            // Another request already generated this session's next question -
            // uk_questions_session_sequence is what actually decided that, not
            // this catch block. The right answer is the row that won, not an
            // error for the request that lost the race.
            return questions.findBySessionIdAndSequenceNo(session.getId(), nextSequence)
                    .orElseThrow(() -> e);
        }
    }

    /**
     * Asks the fallback provider directly - bypassing {@link AiService}, and
     * therefore any further Gemini attempt - for enough candidates to be sure
     * an unused one exists, then takes the first one this session has not
     * already asked.
     *
     * <p>{@code existing.size() + 1} candidates, not "however many the
     * interview has left": {@code FallbackLlmClient.select()} returns the same
     * fixed-order sequence regardless of how many are requested, so this many
     * covers every already-used slot plus exactly one new one, however many
     * questions remain in the interview overall.
     */
    private GeneratedQuestion firstUnusedFallbackCandidate(Interview interview, int nextSequence,
            List<Question> existing) {
        QuestionRequest batchRequest = new QuestionRequest(
                interview.getDomain(),
                interview.getInterviewType(),
                interview.getCandidateType(),
                interview.getExperienceYears(),
                existing.size() + 1,
                interview.getLanguage());

        List<GeneratedQuestion> candidates = fallback.generateQuestions(batchRequest);
        return candidates.stream()
                .filter(candidate -> !isDuplicateInSession(candidate, existing))
                .findFirst()
                .orElseGet(() -> {
                    log.warn("Offline bank has no unused question left for session context "
                            + "{}/{}/{} at sequence {}", interview.getDomain(),
                            interview.getInterviewType(), interview.getCandidateType(), nextSequence);
                    // Deliberately not the caller's problem to guess at - the
                    // final duplicate guard in ensureNextAdaptiveQuestion is
                    // what turns this into a loud failure rather than a
                    // silently repeated question.
                    return candidates.isEmpty() ? null : candidates.getFirst();
                });
    }

    /** Same normalisation {@link Question}'s own text hash uses, without persisting anything. */
    private static boolean isDuplicateInSession(GeneratedQuestion candidate, List<Question> existing) {
        if (candidate == null) {
            return true;
        }
        String normalised = candidate.text().trim().toLowerCase(Locale.ROOT);
        return existing.stream()
                .anyMatch(q -> q.getText().trim().toLowerCase(Locale.ROOT).equals(normalised));
    }
}
