package com.project.proctorinterview.interview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.project.proctorinterview.ai.GeminiLlmClient;
import com.project.proctorinterview.ai.dto.AiDtos.EvaluationResult;
import com.project.proctorinterview.ai.dto.AiDtos.GeneratedQuestion;
import com.project.proctorinterview.ai.dto.AiDtos.QuestionRequest;
import com.project.proctorinterview.answer.AnswerRepository;
import com.project.proctorinterview.common.Enums.CandidateType;
import com.project.proctorinterview.common.Enums.Difficulty;
import com.project.proctorinterview.common.Enums.InterviewType;
import com.project.proctorinterview.common.Enums.Role;
import com.project.proctorinterview.interview.dto.InterviewDtos.CreateInterviewRequest;
import com.project.proctorinterview.question.Question;
import com.project.proctorinterview.question.QuestionRepository;
import com.project.proctorinterview.user.User;
import com.project.proctorinterview.user.UserRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * Phase 6.3: the two things that need a mocked {@link GeminiLlmClient} to
 * observe - the real, HTTP-submitted answer's score reaching the next
 * generation call, and a genuine concurrent race through the real controller
 * wiring. Neither is reachable with {@code ai-mode=MOCK} ({@link
 * AdaptiveQuestionFlowTest}'s context, where Gemini is never called at all)
 * or with {@code question-mode=FIXED} ({@link QuestionModeIntegrationTest}).
 *
 * <p>{@code @MockitoBean} replaces the real {@link GeminiLlmClient} bean, so
 * no live network call happens here.
 */
@SpringBootTest(properties = {
        "app.interview.question-mode=ADAPTIVE",
        "app.interview.ai-mode=GEMINI"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdaptiveGeminiIntegrationTest {

    @Autowired
    private com.project.proctorinterview.TestDatabaseCleaner cleaner;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UserRepository users;
    @Autowired
    private QuestionRepository questions;
    @Autowired
    private AnswerRepository answers;
    @Autowired
    private InterviewService interviewService;
    @Autowired
    private PasswordEncoder encoder;
    @Autowired
    private ObjectMapper json;

    @MockitoBean
    private GeminiLlmClient gemini;

    private String candidateJwt;
    private Long recruiterId;
    private Long candidateId;

    @BeforeEach
    void setUp() {
        cleaner.clean();
        recruiterId = user("iv@test.local", "Recruiter@1", Role.RECRUITER);
        candidateId = user("cand@test.local", "Candidate@1", Role.CANDIDATE);
        candidateJwt = login("cand@test.local", "Candidate@1");

        when(gemini.isAvailable()).thenReturn(true);
        when(gemini.modelName()).thenReturn("gemini-3.1-flash-lite");
    }

    private Long user(String email, String password, Role role) {
        User u = new User();
        u.setEmail(email);
        u.setPasswordHash(encoder.encode(password));
        u.setFullName("Test " + role);
        u.setRole(role);
        u.setEnabled(true);
        return users.save(u).getId();
    }

    private String login(String email, String password) {
        try {
            String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
                    .andReturn().getResponse().getContentAsString();
            return (String) json.readValue(body, Map.class).get("token");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String newInterview(int questionCount) {
        CreateInterviewRequest req = new CreateInterviewRequest();
        req.setCandidateId(candidateId);
        req.setScheduledAt(LocalDateTime.now().plusDays(1));
        req.setCandidateType(CandidateType.FRESHER);
        req.setDomain("Java");
        req.setInterviewType(InterviewType.TECHNICAL);
        req.setQuestionCount(questionCount);
        return interviewService.create(recruiterId, req).getInviteToken();
    }

    private Long start(String token) throws Exception {
        String body = mvc.perform(post("/api/exam/{t}/start", token)
                        .header("Authorization", "Bearer " + candidateJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cameraGranted\":true,\"micGranted\":true}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return ((Number) json.readValue(body, Map.class).get("sessionId")).longValue();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> questionsOf(Long sessionId) throws Exception {
        String body = mvc.perform(get("/api/interview-sessions/{id}/questions", sessionId)
                        .header("Authorization", "Bearer " + candidateJwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return (List<Map<String, Object>>) json.readValue(body, Map.class).get("questions");
    }

    private void answer(Long sessionId, Object questionId, String transcript) throws Exception {
        mvc.perform(post("/api/interview-sessions/{id}/answers", sessionId)
                        .header("Authorization", "Bearer " + candidateJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questionId\":%s,\"rawTranscript\":\"%s\",\"durationSeconds\":30}"
                                .formatted(questionId, transcript)))
                .andExpect(status().isOk());
    }

    // ---- prior-answer context through the real pipeline ------------------------

    @Test
    void priorAnswerContextReachesAdaptiveGenerationThroughTheRealAnswerPipeline() throws Exception {
        when(gemini.generateQuestions(any()))
                .thenReturn(List.of(new GeneratedQuestion("The first question", Difficulty.HARD, List.of("a"))))
                .thenReturn(List.of(new GeneratedQuestion("The second, adapted question", Difficulty.EASY, List.of("b"))));
        // A real evaluation, scored by the real (mocked-provider) pipeline -
        // AnswerService still computes overallScore in Java from these
        // sub-scores, exactly as it does for every other evaluator.
        when(gemini.evaluateAnswer(any()))
                .thenReturn(new EvaluationResult(40, 40, 40, 40, "Needs more depth.", List.of(), List.of("shallow")));

        Long sessionId = start(newInterview(3));
        var q1 = questionsOf(sessionId).get(0);
        answer(sessionId, q1.get("id"), "A shallow answer");

        Question persistedFirst = questions.findBySessionIdOrderBySequenceNoAsc(sessionId).getFirst();
        int actualOverallScore = answers.findByQuestionId(persistedFirst.getId())
                .orElseThrow()
                .getOverallScore();

        ArgumentCaptor<QuestionRequest> captor = ArgumentCaptor.forClass(QuestionRequest.class);
        org.mockito.Mockito.verify(gemini, org.mockito.Mockito.times(2)).generateQuestions(captor.capture());
        QuestionRequest secondCall = captor.getAllValues().get(1);

        assertThat(secondCall.priorAnswer()).isNotNull();
        assertThat(secondCall.priorAnswer().previousDifficulty()).isEqualTo(Difficulty.HARD);
        // Whatever Java actually computed and persisted is what reached Gemini -
        // not a hardcoded number, and not a second, separate scoring path.
        assertThat(secondCall.priorAnswer().previousOverallScore()).isEqualTo(actualOverallScore);
    }

    // ---- concurrency, through the real controller -------------------------------

    /**
     * Racing for question 1, not a later one: {@code POST /answers} always
     * resolves its own generation synchronously within that same request (a
     * question can only be answered once, so two concurrent POSTs for the
     * same question cannot both trigger it), which leaves no way to race a
     * later question through the public API. Two concurrent initial
     * {@code GET}s on a brand-new session - both seeing no questions yet -
     * are the scenario that is actually reachable, and a realistic one: two
     * tabs, or a retried request.
     */
    @Test
    void concurrentNextQuestionRequestsThroughTheControllerResultInExactlyOnePersistedQuestion()
            throws Exception {
        Long sessionId = start(newInterview(5));

        int threadCount = 2;
        // Both threads must reach this barrier before either gets its
        // GeneratedQuestion back, so both have already read "no questions
        // exist yet" and are moments from inserting the same sequence number -
        // the same technique QuestionServiceAdaptiveTest uses, here exercised
        // through the real controller instead of the service directly.
        CyclicBarrier bothReadyToInsert = new CyclicBarrier(threadCount);
        when(gemini.generateQuestions(any())).thenAnswer(invocation -> {
            bothReadyToInsert.await(10, TimeUnit.SECONDS);
            return List.of(new GeneratedQuestion("The racing first question", Difficulty.EASY, List.of("a")));
        });

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        try {
            List<Future<List<Map<String, Object>>>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                futures.add(pool.submit(() -> questionsOf(sessionId)));
            }
            List<List<Map<String, Object>>> results = new java.util.ArrayList<>();
            for (Future<List<Map<String, Object>>> future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }
            assertThat(results).allSatisfy(list -> assertThat(list).hasSize(1));
            assertThat(results.get(0).get(0).get("id")).isEqualTo(results.get(1).get(0).get("id"));
        } finally {
            pool.shutdown();
        }

        assertThat(questions.countBySessionId(sessionId)).isEqualTo(1);
    }
}
