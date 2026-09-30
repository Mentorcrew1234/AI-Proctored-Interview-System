package com.project.proctorinterview.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import com.project.proctorinterview.ai.dto.AiDtos.GeneratedQuestion;
import com.project.proctorinterview.answer.AnswerRepository;
import com.project.proctorinterview.common.Enums.CandidateType;
import com.project.proctorinterview.common.Enums.Difficulty;
import com.project.proctorinterview.common.Enums.InterviewStatus;
import com.project.proctorinterview.common.Enums.InterviewType;
import com.project.proctorinterview.common.Enums.QuestionSource;
import com.project.proctorinterview.common.Enums.Role;
import com.project.proctorinterview.config.InterviewAiProperties;
import com.project.proctorinterview.interview.Interview;
import com.project.proctorinterview.interview.InterviewRepository;
import com.project.proctorinterview.interview.InterviewSession;
import com.project.proctorinterview.interview.InterviewSessionRepository;
import com.project.proctorinterview.question.AdaptiveQuestionWriter;
import com.project.proctorinterview.question.Question;
import com.project.proctorinterview.question.QuestionRepository;
import com.project.proctorinterview.question.QuestionService;
import com.project.proctorinterview.user.User;
import com.project.proctorinterview.user.UserRepository;

/**
 * Phase 5.1: question provenance storage.
 *
 * <p>Proves the two new facts a {@link Question} now carries - which model
 * produced it, and a stable hash of its text - are recorded correctly for
 * both providers, and that nothing about the existing lifecycle (ordering,
 * the session/answer relationship, {@code sequence_no}) changed underneath
 * them. Selection, reuse and cross-interview variation are deliberately not
 * exercised here - they do not exist yet.
 */
@DataJpaTest
@ActiveProfiles("test")
class QuestionProvenanceTest {

    @Autowired
    private UserRepository users;
    @Autowired
    private InterviewRepository interviews;
    @Autowired
    private InterviewSessionRepository sessions;
    @Autowired
    private QuestionRepository questions;
    @Autowired
    private AnswerRepository answers;

    private InterviewSession newSession() {
        User recruiter = new User();
        recruiter.setEmail("recruiter-" + UUID.randomUUID() + "@test.local");
        recruiter.setPasswordHash("$2a$10$notarealhash");
        recruiter.setFullName("Test Recruiter");
        recruiter.setRole(Role.RECRUITER);
        users.save(recruiter);

        User candidate = new User();
        candidate.setEmail("candidate-" + UUID.randomUUID() + "@test.local");
        candidate.setPasswordHash("$2a$10$notarealhash");
        candidate.setFullName("Test Candidate");
        candidate.setRole(Role.CANDIDATE);
        users.save(candidate);

        Interview interview = new Interview();
        interview.setRecruiter(recruiter);
        interview.setCandidate(candidate);
        interview.setScheduledAt(Instant.now().plus(1, ChronoUnit.DAYS));
        interview.setCandidateType(CandidateType.FRESHER);
        interview.setDomain("Java");
        interview.setInterviewType(InterviewType.TECHNICAL);
        interview.setQuestionCount(5);
        interview.setStatus(InterviewStatus.SCHEDULED);
        interview.setInviteToken(UUID.randomUUID().toString());
        interviews.save(interview);

        InterviewSession session = new InterviewSession();
        session.setInterview(interview);
        session.setCameraGranted(true);
        session.setMicGranted(true);
        return sessions.save(session);
    }

    private QuestionService questionServiceWith(GeminiLlmClient gemini, InterviewAiProperties.AiMode mode) {
        FallbackLlmClient fallback = new FallbackLlmClient();
        fallback.loadBank();
        AiService ai = new AiService(gemini, fallback, new InterviewAiProperties(mode), new AiHealthRegistry());
        return new QuestionService(questions, ai, answers, fallback, new AdaptiveQuestionWriter(questions));
    }

    // ---- storage itself -------------------------------------------------

    @Test
    void aQuestionPersistsWithItsModelNameAndAComputedTextHash() {
        InterviewSession session = newSession();

        Question question = new Question();
        question.setSession(session);
        question.setSequenceNo(1);
        question.setText("How would you debug a slow endpoint in production?");
        question.setDifficulty(Difficulty.MEDIUM);
        question.setExpectedPoints(List.of("profiling", "logging"));
        question.setSource(QuestionSource.LLM);
        question.setModelName("gemini-3.1-flash-lite");
        Long id = questions.saveAndFlush(question).getId();

        Question reloaded = questions.findById(id).orElseThrow();
        assertThat(reloaded.getModelName()).isEqualTo("gemini-3.1-flash-lite");
        assertThat(reloaded.getTextHash()).isNotBlank().hasSize(64);
    }

    @Test
    void theTextHashIsComputedAutomaticallyWhenNotSetByTheCaller() {
        InterviewSession session = newSession();

        Question question = new Question();
        question.setSession(session);
        question.setSequenceNo(1);
        question.setText("Same question, same hash.");
        question.setDifficulty(Difficulty.EASY);
        question.setSource(QuestionSource.BANK);
        question.setModelName("offline-fallback");
        // textHash deliberately left unset - callers must not have to remember it.
        Long id = questions.saveAndFlush(question).getId();

        assertThat(questions.findById(id).orElseThrow().getTextHash()).isNotBlank();
    }

    @Test
    void identicalTextProducesTheSameHashAndDifferentTextDoesNot() {
        InterviewSession session = newSession();

        Question first = new Question();
        first.setSession(session);
        first.setSequenceNo(1);
        first.setText("  How would you debug a slow endpoint?  ");
        first.setDifficulty(Difficulty.MEDIUM);
        first.setSource(QuestionSource.BANK);
        questions.saveAndFlush(first);

        Question sameTextDifferentCase = new Question();
        sameTextDifferentCase.setSession(session);
        sameTextDifferentCase.setSequenceNo(2);
        sameTextDifferentCase.setText("HOW WOULD YOU DEBUG A SLOW ENDPOINT?");
        sameTextDifferentCase.setDifficulty(Difficulty.MEDIUM);
        sameTextDifferentCase.setSource(QuestionSource.BANK);
        questions.saveAndFlush(sameTextDifferentCase);

        Question different = new Question();
        different.setSession(session);
        different.setSequenceNo(3);
        different.setText("How would you design a rate limiter?");
        different.setDifficulty(Difficulty.MEDIUM);
        different.setSource(QuestionSource.BANK);
        questions.saveAndFlush(different);

        assertThat(first.getTextHash()).isEqualTo(sameTextDifferentCase.getTextHash());
        assertThat(first.getTextHash()).isNotEqualTo(different.getTextHash());
    }

    // ---- provenance through the real generation path ---------------------

    @Test
    void llmGeneratedQuestionsRecordTheirSourceAndModel() {
        GeminiLlmClient gemini = mock(GeminiLlmClient.class);
        when(gemini.isAvailable()).thenReturn(true);
        when(gemini.modelName()).thenReturn("gemini-3.1-flash-lite");
        when(gemini.generateQuestions(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(
                new GeneratedQuestion("A generated question?", Difficulty.MEDIUM, List.of("point"))));
        QuestionService service = questionServiceWith(gemini, InterviewAiProperties.AiMode.GEMINI);

        InterviewSession session = newSession();
        List<Question> saved = service.ensureQuestions(session);

        assertThat(saved).isNotEmpty();
        assertThat(saved).allSatisfy(q -> {
            assertThat(q.getSource()).isEqualTo(QuestionSource.LLM);
            assertThat(q.getModelName()).isEqualTo("gemini-3.1-flash-lite");
            assertThat(q.getTextHash()).isNotBlank();
        });
    }

    @Test
    void bankGeneratedQuestionsRecordTheirSourceAndModel() {
        GeminiLlmClient gemini = mock(GeminiLlmClient.class);
        when(gemini.isAvailable()).thenReturn(false);
        QuestionService service = questionServiceWith(gemini, InterviewAiProperties.AiMode.GEMINI);

        InterviewSession session = newSession();
        List<Question> saved = service.ensureQuestions(session);

        assertThat(saved).isNotEmpty();
        assertThat(saved).allSatisfy(q -> {
            assertThat(q.getSource()).isEqualTo(QuestionSource.BANK);
            assertThat(q.getModelName()).isEqualTo("offline-fallback");
        });
    }

    // ---- existing lifecycle, unchanged ------------------------------------

    @Test
    void sequenceNumbersStayAuthoritativeAndOrderedAfterTheProvenanceChange() {
        GeminiLlmClient gemini = mock(GeminiLlmClient.class);
        when(gemini.isAvailable()).thenReturn(false);
        QuestionService service = questionServiceWith(gemini, InterviewAiProperties.AiMode.GEMINI);

        InterviewSession session = newSession();
        List<Question> saved = service.ensureQuestions(session);

        List<Integer> sequenceNumbers = saved.stream().map(Question::getSequenceNo).toList();
        assertThat(sequenceNumbers).isSorted();
        assertThat(sequenceNumbers).containsExactlyElementsOf(
                java.util.stream.IntStream.rangeClosed(1, saved.size()).boxed().toList());

        // Re-calling must not create a second set or renumber anything.
        List<Question> again = service.ensureQuestions(session);
        assertThat(again).hasSameSizeAs(saved);
        assertThat(questions.findBySessionIdOrderBySequenceNoAsc(session.getId())).hasSize(saved.size());
    }

    @Test
    void aQuestionWithoutAModelNameStillPersistsForBackwardCompatibility() {
        InterviewSession session = newSession();

        Question question = new Question();
        question.setSession(session);
        question.setSequenceNo(1);
        question.setText("A question saved the way pre-Phase-5.1 code would have.");
        question.setDifficulty(Difficulty.EASY);
        question.setSource(QuestionSource.BANK);
        // modelName deliberately left null, as every pre-migration row is.
        Long id = questions.saveAndFlush(question).getId();

        Question reloaded = questions.findById(id).orElseThrow();
        assertThat(reloaded.getModelName()).isNull();
        assertThat(reloaded.getTextHash()).isNotBlank();
    }
}
