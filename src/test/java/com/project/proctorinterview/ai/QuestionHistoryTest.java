package com.project.proctorinterview.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import com.project.proctorinterview.ai.dto.AiDtos.GeneratedQuestion;
import com.project.proctorinterview.ai.dto.AiDtos.QuestionRequest;
import com.project.proctorinterview.common.Enums.CandidateType;
import com.project.proctorinterview.common.Enums.Difficulty;
import com.project.proctorinterview.common.Enums.InterviewLanguage;
import com.project.proctorinterview.common.Enums.InterviewStatus;
import com.project.proctorinterview.common.Enums.InterviewType;
import com.project.proctorinterview.common.Enums.QuestionSource;
import com.project.proctorinterview.common.Enums.Role;
import com.project.proctorinterview.answer.AnswerRepository;
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
 * Phase 5.2: cross-interview question variety.
 *
 * <p>Proves the bounded history query is scoped correctly and actually
 * reaches {@link GeminiLlmClient} through {@link QuestionRequest}, and that
 * every existing guarantee - count, ordering, no within-interview duplicates,
 * fallback behaviour - survives threading it through. No live Gemini call is
 * made anywhere here; the LLM path uses a mocked {@link GeminiLlmClient} the
 * same way {@link AiServiceFallbackTest} does.
 */
@DataJpaTest
@ActiveProfiles("test")
class QuestionHistoryTest {

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

    private InterviewSession newSession(String domain, InterviewType type,
            CandidateType candidateType, InterviewLanguage language, int questionCount) {
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
        interview.setLanguage(language);
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

    private Question savedQuestion(InterviewSession session, int sequenceNo, String text) {
        Question question = new Question();
        question.setSession(session);
        question.setSequenceNo(sequenceNo);
        question.setText(text);
        question.setDifficulty(Difficulty.MEDIUM);
        question.setSource(QuestionSource.BANK);
        return questions.saveAndFlush(question);
    }

    private QuestionService questionServiceWith(GeminiLlmClient gemini) {
        FallbackLlmClient fallback = new FallbackLlmClient();
        fallback.loadBank();
        AiService ai = new AiService(gemini, fallback,
                new InterviewAiProperties(InterviewAiProperties.AiMode.GEMINI), new AiHealthRegistry());
        return new QuestionService(questions, ai, answers, fallback, new AdaptiveQuestionWriter(questions));
    }

    // ---- the repository query itself --------------------------------------

    @Test
    void previousQuestionsAreRetrievedOnlyForTheMatchingContext() {
        InterviewSession matching = newSession("Java", InterviewType.TECHNICAL,
                CandidateType.FRESHER, InterviewLanguage.ENGLISH, 5);
        savedQuestion(matching, 1, "A matching-context question");

        InterviewSession differentDomain = newSession("Python", InterviewType.TECHNICAL,
                CandidateType.FRESHER, InterviewLanguage.ENGLISH, 5);
        savedQuestion(differentDomain, 1, "A different-domain question");

        InterviewSession differentCandidateType = newSession("Java", InterviewType.TECHNICAL,
                CandidateType.EXPERIENCED, InterviewLanguage.ENGLISH, 5);
        savedQuestion(differentCandidateType, 1, "A different-candidate-type question");

        InterviewSession differentType = newSession("Java", InterviewType.HR_GENERAL,
                CandidateType.FRESHER, InterviewLanguage.ENGLISH, 5);
        savedQuestion(differentType, 1, "A different-interview-type question");

        List<String> found = questions.findRecentTextsByContext("Java", InterviewType.TECHNICAL,
                CandidateType.FRESHER, InterviewLanguage.ENGLISH, PageRequest.of(0, 10));

        assertThat(found).containsExactly("A matching-context question");
    }

    @Test
    void historyRetrievalIsBounded() {
        InterviewSession session = newSession("Java", InterviewType.TECHNICAL,
                CandidateType.FRESHER, InterviewLanguage.ENGLISH, 5);
        for (int i = 1; i <= 12; i++) {
            savedQuestion(session, i, "Question number " + i);
        }

        List<String> found = questions.findRecentTextsByContext("Java", InterviewType.TECHNICAL,
                CandidateType.FRESHER, InterviewLanguage.ENGLISH, PageRequest.of(0, 5));

        // Bounded to the page size, never the full 12 that exist.
        assertThat(found).hasSize(5);
    }

    // ---- the full generation path -------------------------------------------

    @Test
    void historyReachesTheGenerationLayer() {
        InterviewSession earlier = newSession("Java", InterviewType.TECHNICAL,
                CandidateType.FRESHER, InterviewLanguage.ENGLISH, 5);
        savedQuestion(earlier, 1, "An already-used scenario question");

        GeminiLlmClient gemini = mock(GeminiLlmClient.class);
        when(gemini.isAvailable()).thenReturn(true);
        when(gemini.modelName()).thenReturn("gemini-3.1-flash-lite");
        when(gemini.generateQuestions(any())).thenReturn(List.of(
                new GeneratedQuestion("A brand new question", Difficulty.MEDIUM, List.of("point"))));
        QuestionService service = questionServiceWith(gemini);

        InterviewSession current = newSession("Java", InterviewType.TECHNICAL,
                CandidateType.FRESHER, InterviewLanguage.ENGLISH, 5);
        service.ensureQuestions(current);

        ArgumentCaptor<QuestionRequest> captor = ArgumentCaptor.forClass(QuestionRequest.class);
        verify(gemini).generateQuestions(captor.capture());
        assertThat(captor.getValue().avoidQuestionTexts()).containsExactly("An already-used scenario question");
    }

    @Test
    void identicalConfigurationsCanReceiveDifferentAvoidContextAsHistoryGrows() {
        GeminiLlmClient gemini = mock(GeminiLlmClient.class);
        when(gemini.isAvailable()).thenReturn(true);
        when(gemini.modelName()).thenReturn("gemini-3.1-flash-lite");
        when(gemini.generateQuestions(any())).thenReturn(List.of(
                new GeneratedQuestion("The first interview's question", Difficulty.EASY, List.of("point"))));
        QuestionService service = questionServiceWith(gemini);

        InterviewSession first = newSession("Java", InterviewType.TECHNICAL,
                CandidateType.FRESHER, InterviewLanguage.ENGLISH, 1);
        service.ensureQuestions(first);

        ArgumentCaptor<QuestionRequest> captor = ArgumentCaptor.forClass(QuestionRequest.class);
        verify(gemini).generateQuestions(captor.capture());
        // Nothing came before it.
        assertThat(captor.getValue().avoidQuestionTexts()).isEmpty();

        when(gemini.generateQuestions(any())).thenReturn(List.of(
                new GeneratedQuestion("The second interview's question", Difficulty.EASY, List.of("point"))));
        InterviewSession second = newSession("Java", InterviewType.TECHNICAL,
                CandidateType.FRESHER, InterviewLanguage.ENGLISH, 1);
        service.ensureQuestions(second);

        ArgumentCaptor<QuestionRequest> secondCaptor = ArgumentCaptor.forClass(QuestionRequest.class);
        verify(gemini, org.mockito.Mockito.times(2)).generateQuestions(secondCaptor.capture());
        // Same domain/type/candidate-type/language as `first`, but this time
        // the first interview's own question is offered as avoid-context.
        assertThat(secondCaptor.getValue().avoidQuestionTexts())
                .containsExactly("The first interview's question");
    }

    @Test
    void questionCountAndOrderingRemainCorrectWithHistoryPresent() {
        InterviewSession earlier = newSession("Java", InterviewType.TECHNICAL,
                CandidateType.FRESHER, InterviewLanguage.ENGLISH, 5);
        savedQuestion(earlier, 1, "Prior question one");
        savedQuestion(earlier, 2, "Prior question two");

        GeminiLlmClient gemini = mock(GeminiLlmClient.class);
        when(gemini.isAvailable()).thenReturn(true);
        when(gemini.modelName()).thenReturn("gemini-3.1-flash-lite");
        when(gemini.generateQuestions(any())).thenReturn(List.of(
                new GeneratedQuestion("Q1", Difficulty.EASY, List.of("a")),
                new GeneratedQuestion("Q2", Difficulty.MEDIUM, List.of("b")),
                new GeneratedQuestion("Q3", Difficulty.HARD, List.of("c"))));
        QuestionService service = questionServiceWith(gemini);

        InterviewSession current = newSession("Java", InterviewType.TECHNICAL,
                CandidateType.FRESHER, InterviewLanguage.ENGLISH, 3);
        List<Question> saved = service.ensureQuestions(current);

        assertThat(saved).hasSize(3);
        assertThat(saved).extracting(Question::getSequenceNo).containsExactly(1, 2, 3);
        assertThat(saved).extracting(Question::getText).containsExactly("Q1", "Q2", "Q3");
        assertThat(saved).extracting(Question::getText).doesNotHaveDuplicates();
    }

    // ---- fallback and the no-history case ------------------------------------

    @Test
    void fallbackBehaviourIsUnaffectedByAvoidHistoryPlumbing() {
        InterviewSession earlier = newSession("Java", InterviewType.TECHNICAL,
                CandidateType.FRESHER, InterviewLanguage.ENGLISH, 5);
        savedQuestion(earlier, 1, "Prior question that only matters if Gemini is used");

        GeminiLlmClient gemini = mock(GeminiLlmClient.class);
        when(gemini.isAvailable()).thenReturn(false);
        QuestionService service = questionServiceWith(gemini);

        InterviewSession current = newSession("Java", InterviewType.TECHNICAL,
                CandidateType.FRESHER, InterviewLanguage.ENGLISH, 5);
        List<Question> saved = service.ensureQuestions(current);

        assertThat(saved).hasSize(5);
        assertThat(saved).allSatisfy(q -> {
            assertThat(q.getSource()).isEqualTo(QuestionSource.BANK);
            assertThat(q.getModelName()).isEqualTo("offline-fallback");
        });
        verify(gemini, org.mockito.Mockito.never()).generateQuestions(any());
    }

    @Test
    void generationBehaviourIsUnchangedWhenNoHistoryExists() {
        GeminiLlmClient gemini = mock(GeminiLlmClient.class);
        when(gemini.isAvailable()).thenReturn(true);
        when(gemini.modelName()).thenReturn("gemini-3.1-flash-lite");
        when(gemini.generateQuestions(any())).thenReturn(List.of(
                new GeneratedQuestion("The only question so far", Difficulty.EASY, List.of("point"))));
        QuestionService service = questionServiceWith(gemini);

        InterviewSession firstEver = newSession("Rust", InterviewType.TECHNICAL,
                CandidateType.FRESHER, InterviewLanguage.ENGLISH, 1);
        List<Question> saved = service.ensureQuestions(firstEver);

        assertThat(saved).hasSize(1);
        ArgumentCaptor<QuestionRequest> captor = ArgumentCaptor.forClass(QuestionRequest.class);
        verify(gemini).generateQuestions(captor.capture());
        assertThat(captor.getValue().avoidQuestionTexts()).isEmpty();
    }
}
