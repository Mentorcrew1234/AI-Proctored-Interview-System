package com.project.proctorinterview.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.project.proctorinterview.ai.dto.AiDtos.GeneratedQuestion;
import com.project.proctorinterview.ai.dto.AiDtos.PriorAnswerContext;
import com.project.proctorinterview.ai.dto.AiDtos.QuestionRequest;
import com.project.proctorinterview.answer.Answer;
import com.project.proctorinterview.answer.AnswerRepository;
import com.project.proctorinterview.common.Enums.CandidateType;
import com.project.proctorinterview.common.Enums.Difficulty;
import com.project.proctorinterview.common.Enums.InterviewLanguage;
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
 * Phase 6.2: the adaptive question-generation backend foundation.
 *
 * <p>Nothing here is wired into any controller and {@code app.interview.
 * question-mode} still defaults to {@code FIXED} - see
 * {@link com.project.proctorinterview.config.QuestionModePropertiesTest} for
 * that guarantee. These tests call
 * {@link QuestionService#ensureNextAdaptiveQuestion} directly, the only way it
 * is reachable at this phase. No live Gemini call is made anywhere here.
 *
 * <p>{@code @SpringBootTest} rather than {@code @DataJpaTest} deliberately:
 * {@code @DataJpaTest} wraps each test method in one uncommitted transaction,
 * which {@link AdaptiveQuestionWriter}'s {@code REQUIRES_NEW} insert - a
 * genuinely separate transaction - cannot see, since nothing set up earlier in
 * the same test method has committed yet. That combination produces a foreign
 * key violation on every insert, not a bug in the production code; a plain
 * {@code @SpringBootTest} commits normally, the same as the running
 * application does, which is what {@code REQUIRES_NEW} is meant to interact
 * with.
 */
@SpringBootTest
@ActiveProfiles("test")
class QuestionServiceAdaptiveTest {

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
    @Autowired
    private AdaptiveQuestionWriter adaptiveWriter;

    private InterviewSession newSession(String domain, InterviewType type,
            CandidateType candidateType, int questionCount) {
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
        interview.setCandidateType(candidateType);
        interview.setDomain(domain);
        interview.setLanguage(InterviewLanguage.ENGLISH);
        interview.setInterviewType(type);
        interview.setQuestionCount(questionCount);
        interview.setStatus(InterviewStatus.SCHEDULED);
        interview.setInviteToken(UUID.randomUUID().toString());
        interviews.save(interview);

        InterviewSession session = new InterviewSession();
        session.setInterview(interview);
        session.setCameraGranted(true);
        session.setMicGranted(true);
        return sessions.save(session);
    }

    private Question savedQuestion(InterviewSession session, int sequenceNo, String text,
            Difficulty difficulty) {
        Question question = new Question();
        question.setSession(session);
        question.setSequenceNo(sequenceNo);
        question.setText(text);
        question.setDifficulty(difficulty);
        question.setSource(QuestionSource.BANK);
        return questions.saveAndFlush(question);
    }

    private void savedAnswer(Question question, int overallScore) {
        Answer answer = new Answer();
        answer.setQuestion(question);
        answer.setRawTranscript("an answer");
        answer.setCleanTranscript("an answer");
        answer.setDurationSeconds(30);
        answer.setTechnicalScore(overallScore);
        answer.setRelevanceScore(overallScore);
        answer.setProblemSolvingScore(overallScore);
        answer.setCommunicationScore(overallScore);
        answer.setOverallScore(overallScore);
        answer.setEvaluatedAt(Instant.now());
        answers.saveAndFlush(answer);
    }

    private QuestionService questionServiceWith(GeminiLlmClient gemini) {
        FallbackLlmClient fallback = new FallbackLlmClient();
        fallback.loadBank();
        AiService ai = new AiService(gemini, fallback,
                new InterviewAiProperties(InterviewAiProperties.AiMode.GEMINI), new AiHealthRegistry());
        return new QuestionService(questions, ai, answers, fallback, adaptiveWriter);
    }

    private GeminiLlmClient geminiReturning(String text, Difficulty difficulty) {
        GeminiLlmClient gemini = mock(GeminiLlmClient.class);
        when(gemini.isAvailable()).thenReturn(true);
        when(gemini.modelName()).thenReturn("gemini-3.1-flash-lite");
        when(gemini.generateQuestions(any()))
                .thenReturn(List.of(new GeneratedQuestion(text, difficulty, List.of("a point"))));
        return gemini;
    }

    // ---- prior-answer context ----------------------------------------------

    @Test
    void firstAdaptiveQuestionCanBeGeneratedWithoutPriorAnswerContext() {
        GeminiLlmClient gemini = geminiReturning("The first adaptive question", Difficulty.EASY);
        QuestionService service = questionServiceWith(gemini);
        InterviewSession session = newSession("Java", InterviewType.TECHNICAL, CandidateType.FRESHER, 5);

        Question saved = service.ensureNextAdaptiveQuestion(session);

        assertThat(saved).isNotNull();
        assertThat(saved.getSequenceNo()).isEqualTo(1);

        ArgumentCaptor<QuestionRequest> captor = ArgumentCaptor.forClass(QuestionRequest.class);
        verify(gemini).generateQuestions(captor.capture());
        assertThat(captor.getValue().priorAnswer()).isNull();
        assertThat(captor.getValue().count()).isEqualTo(1);
    }

    @Test
    void afterQuestionOneIsAnsweredGeneratingQuestionTwoUsesItsDifficultyAndOverallScore() {
        InterviewSession session = newSession("Java", InterviewType.TECHNICAL, CandidateType.FRESHER, 5);
        Question first = savedQuestion(session, 1, "The first question", Difficulty.HARD);
        savedAnswer(first, 42);

        GeminiLlmClient gemini = geminiReturning("The second, adapted question", Difficulty.EASY);
        QuestionService service = questionServiceWith(gemini);

        Question second = service.ensureNextAdaptiveQuestion(session);

        assertThat(second).isNotNull();
        assertThat(second.getSequenceNo()).isEqualTo(2);

        ArgumentCaptor<QuestionRequest> captor = ArgumentCaptor.forClass(QuestionRequest.class);
        verify(gemini).generateQuestions(captor.capture());
        assertThat(captor.getValue().priorAnswer()).isEqualTo(new PriorAnswerContext(Difficulty.HARD, 42));
    }

    @Test
    void aQuestionIsNotGeneratedWhileThePreviousOneIsStillUnanswered() {
        InterviewSession session = newSession("Java", InterviewType.TECHNICAL, CandidateType.FRESHER, 5);
        savedQuestion(session, 1, "An unanswered question", Difficulty.EASY);

        GeminiLlmClient gemini = geminiReturning("Should never be requested", Difficulty.EASY);
        QuestionService service = questionServiceWith(gemini);

        Question result = service.ensureNextAdaptiveQuestion(session);

        assertThat(result).isNull();
        assertThat(questions.countBySessionId(session.getId())).isEqualTo(1);
        verify(gemini, never()).generateQuestions(any());
    }

    // ---- sequencing and the questionCount boundary --------------------------

    @Test
    void sequenceNoIsCorrectAcrossSeveralAdaptiveQuestions() {
        InterviewSession session = newSession("Java", InterviewType.TECHNICAL, CandidateType.FRESHER, 3);

        GeminiLlmClient gemini1 = geminiReturning("Question one", Difficulty.EASY);
        Question q1 = questionServiceWith(gemini1).ensureNextAdaptiveQuestion(session);
        savedAnswer(q1, 70);

        GeminiLlmClient gemini2 = geminiReturning("Question two", Difficulty.MEDIUM);
        Question q2 = questionServiceWith(gemini2).ensureNextAdaptiveQuestion(session);
        savedAnswer(q2, 80);

        GeminiLlmClient gemini3 = geminiReturning("Question three", Difficulty.HARD);
        Question q3 = questionServiceWith(gemini3).ensureNextAdaptiveQuestion(session);

        assertThat(List.of(q1.getSequenceNo(), q2.getSequenceNo(), q3.getSequenceNo()))
                .containsExactly(1, 2, 3);
    }

    @Test
    void noQuestionIsGeneratedAfterQuestionCountIsReached() {
        InterviewSession session = newSession("Java", InterviewType.TECHNICAL, CandidateType.FRESHER, 1);
        Question only = savedQuestion(session, 1, "The only question this interview needs", Difficulty.EASY);
        savedAnswer(only, 90);

        GeminiLlmClient gemini = geminiReturning("Should never be requested", Difficulty.EASY);
        QuestionService service = questionServiceWith(gemini);

        Question result = service.ensureNextAdaptiveQuestion(session);

        assertThat(result).isNull();
        assertThat(questions.countBySessionId(session.getId())).isEqualTo(1);
        verify(gemini, never()).generateQuestions(any());
    }

    // ---- avoid-history reuse --------------------------------------------------

    @Test
    void existingSessionQuestionsAreIncludedInAvoidHistory() {
        InterviewSession session = newSession("Java", InterviewType.TECHNICAL, CandidateType.FRESHER, 5);
        Question first = savedQuestion(session, 1, "A question already asked in this interview",
                Difficulty.EASY);
        savedAnswer(first, 65);

        GeminiLlmClient gemini = geminiReturning("A new question", Difficulty.MEDIUM);
        questionServiceWith(gemini).ensureNextAdaptiveQuestion(session);

        ArgumentCaptor<QuestionRequest> captor = ArgumentCaptor.forClass(QuestionRequest.class);
        verify(gemini).generateQuestions(captor.capture());
        assertThat(captor.getValue().avoidQuestionTexts())
                .contains("A question already asked in this interview");
    }

    // ---- provenance -----------------------------------------------------------

    @Test
    void theAdaptivelyGeneratedQuestionHasCorrectProvenance() {
        GeminiLlmClient gemini = geminiReturning("A freshly adapted question", Difficulty.MEDIUM);
        QuestionService service = questionServiceWith(gemini);
        InterviewSession session = newSession("Java", InterviewType.TECHNICAL, CandidateType.FRESHER, 5);

        Question saved = service.ensureNextAdaptiveQuestion(session);

        assertThat(saved.getSource()).isEqualTo(QuestionSource.LLM);
        assertThat(saved.getModelName()).isEqualTo("gemini-3.1-flash-lite");
        assertThat(saved.getTextHash()).isNotBlank().hasSize(64);
    }

    // ---- fallback: no duplicates within one session --------------------------

    @Test
    void repeatedFallbackGenerationCannotProduceDuplicateQuestionTextWithinASession() {
        GeminiLlmClient gemini = mock(GeminiLlmClient.class);
        when(gemini.isAvailable()).thenReturn(false);
        InterviewSession session = newSession("Java", InterviewType.TECHNICAL, CandidateType.FRESHER, 5);
        QuestionService service = questionServiceWith(gemini);

        List<String> texts = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Question next = service.ensureNextAdaptiveQuestion(session);
            assertThat(next).isNotNull();
            assertThat(next.getSource()).isEqualTo(QuestionSource.BANK);
            texts.add(next.getText());
            savedAnswer(next, 60);
        }

        assertThat(texts).doesNotHaveDuplicates();
        assertThat(questions.countBySessionId(session.getId())).isEqualTo(5);
    }

    // ---- concurrency ------------------------------------------------------------

    @Test
    void concurrentGenerationOfTheSameNextQuestionResultsInExactlyOnePersistedRow() throws Exception {
        InterviewSession created = newSession("Java", InterviewType.TECHNICAL, CandidateType.FRESHER, 5);
        Long sessionId = created.getId();
        // Fetched directly, not through the lazy session -> interview
        // association: sessions.findById(sessionId) alone would hand each
        // thread an uninitialised proxy tied to a mini-transaction that
        // already closed by the time ensureNextAdaptiveQuestion touches it -
        // exactly the open-in-view: false trap this codebase's own
        // ExamService.requireOwnedSession avoids by touching the association
        // itself before returning. Fetching the interview separately sidesteps
        // it without needing a live session for the handoff between threads.
        Long interviewId = created.getInterview().getId();

        int threadCount = 2;
        // Both threads must reach this barrier before either gets its
        // GeneratedQuestion back - and ensureNextAdaptiveQuestion only calls
        // Gemini AFTER reading existing questions, so by the time both are
        // released here, both have already independently computed the SAME
        // next sequence number and are moments from inserting it. A plain
        // start-together latch was not enough: with a near-instant mock, one
        // thread's whole read-generate-insert cycle routinely finished before
        // the other even began its read, so no collision ever happened.
        CyclicBarrier bothReadyToInsert = new CyclicBarrier(threadCount);
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        try {
            List<Future<Question>> futures = new ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                futures.add(pool.submit(() -> {
                    // Each thread re-reads its own session, the same way two
                    // separate HTTP requests would.
                    GeminiLlmClient gemini = mock(GeminiLlmClient.class);
                    when(gemini.isAvailable()).thenReturn(true);
                    when(gemini.modelName()).thenReturn("gemini-3.1-flash-lite");
                    when(gemini.generateQuestions(any())).thenAnswer(invocation -> {
                        bothReadyToInsert.await(10, TimeUnit.SECONDS);
                        return List.of(new GeneratedQuestion("The racing question",
                                Difficulty.MEDIUM, List.of("point")));
                    });
                    QuestionService service = questionServiceWith(gemini);
                    InterviewSession threadSession = sessions.findById(sessionId).orElseThrow();
                    threadSession.setInterview(interviews.findById(interviewId).orElseThrow());

                    return service.ensureNextAdaptiveQuestion(threadSession);
                }));
            }

            List<Question> results = new ArrayList<>();
            for (Future<Question> future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }

            assertThat(results).allSatisfy(q -> assertThat(q).isNotNull());
            assertThat(results.get(0).getId()).isEqualTo(results.get(1).getId());
            assertThat(questions.countBySessionId(sessionId)).isEqualTo(1);
        } finally {
            pool.shutdown();
        }
    }
}
