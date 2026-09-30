package com.project.proctorinterview.interview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.project.proctorinterview.common.Enums.CandidateType;
import com.project.proctorinterview.common.Enums.InterviewType;
import com.project.proctorinterview.common.Enums.QuestionSource;
import com.project.proctorinterview.common.Enums.Role;
import com.project.proctorinterview.interview.dto.InterviewDtos.CreateInterviewRequest;
import com.project.proctorinterview.question.Question;
import com.project.proctorinterview.question.QuestionRepository;
import com.project.proctorinterview.user.User;
import com.project.proctorinterview.user.UserRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * Phase 6.3: adaptive questioning wired into the real interview flow.
 *
 * <p>{@code app.interview.question-mode=ADAPTIVE} for this whole class - a
 * separate cached Spring context from {@link SessionLifecycleTest}, which
 * keeps running against the default ({@code FIXED}) and is the real
 * fixed-mode regression check: it was not touched by this phase and still
 * passes unchanged.
 *
 * <p>{@code app.interview.ai-mode} is left at its test-profile default
 * ({@code MOCK}), so every generated question here comes from the offline
 * fallback - exercising "fallback still works in adaptive mode" as a side
 * effect of every test in this class, not just one of them.
 */
@SpringBootTest(properties = "app.interview.question-mode=ADAPTIVE")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdaptiveQuestionFlowTest {

    @Autowired
    private com.project.proctorinterview.TestDatabaseCleaner cleaner;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UserRepository users;
    @Autowired
    private QuestionRepository questions;
    @Autowired
    private InterviewService interviewService;
    @Autowired
    private PasswordEncoder encoder;
    @Autowired
    private ObjectMapper json;

    private String candidateJwt;
    private Long recruiterId;
    private Long candidateId;

    @BeforeEach
    void setUp() {
        cleaner.clean();

        recruiterId = user("iv@test.local", "Recruiter@1", Role.RECRUITER);
        candidateId = user("cand@test.local", "Candidate@1", Role.CANDIDATE);
        candidateJwt = login("cand@test.local", "Candidate@1");
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

    private Long start(String token) throws Exception {
        String body = mvc.perform(post("/api/exam/{t}/start", token)
                        .header("Authorization", "Bearer " + candidateJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cameraGranted\":true,\"micGranted\":true,\"browserInfo\":\"test\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return ((Number) json.readValue(body, Map.class).get("sessionId")).longValue();
    }

    private List<Map<String, Object>> questionsOf(Long sessionId) throws Exception {
        String body = mvc.perform(get("/api/interview-sessions/{id}/questions", sessionId)
                        .header("Authorization", "Bearer " + candidateJwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        @SuppressWarnings("unchecked")
        var list = (List<Map<String, Object>>) json.readValue(body, Map.class).get("questions");
        return list;
    }

    private Map<String, Object> answer(Long sessionId, Object questionId) throws Exception {
        String body = mvc.perform(post("/api/interview-sessions/{id}/answers", sessionId)
                        .header("Authorization", "Bearer " + candidateJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questionId\":%s,\"rawTranscript\":\"I would check the query plan and add an index.\",\"durationSeconds\":30}"
                                .formatted(questionId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readValue(body, Map.class);
    }

    // ---- bootstrap ------------------------------------------------------------

    @Test
    void adaptiveModeGeneratesOnlyTheFirstQuestionAtStart() throws Exception {
        Long sessionId = start(newInterview(3));

        var list = questionsOf(sessionId);

        assertThat(list).hasSize(1);
        assertThat(questions.countBySessionId(sessionId)).isEqualTo(1);
    }

    // ---- answer -> next question -----------------------------------------------

    @Test
    void answeringGeneratesTheNextAdaptiveQuestion() throws Exception {
        Long sessionId = start(newInterview(3));
        var first = questionsOf(sessionId);

        var response = answer(sessionId, first.get(0).get("id"));

        // The POST response itself already reflects the newly generated
        // question - proving the trigger fires inside answer submission, not
        // only on a subsequent GET.
        assertThat(((Number) response.get("totalQuestions")).intValue()).isEqualTo(2);
        assertThat(((Number) response.get("answeredCount")).intValue()).isEqualTo(1);
        assertThat((Boolean) response.get("complete")).isFalse();

        var reloaded = questionsOf(sessionId);
        assertThat(reloaded).hasSize(2);
        assertThat(reloaded.get(1).get("id")).isNotEqualTo(first.get(0).get("id"));
    }

    // ---- questionCount boundary -------------------------------------------------

    @Test
    void adaptiveGenerationStopsAtQuestionCount() throws Exception {
        Long sessionId = start(newInterview(2));
        var q1 = questionsOf(sessionId).get(0);

        var afterFirst = answer(sessionId, q1.get("id"));
        assertThat((Boolean) afterFirst.get("complete")).isFalse();

        var q2 = questionsOf(sessionId).get(1);
        var afterSecond = answer(sessionId, q2.get("id"));

        // The final answer must not generate a third question.
        assertThat(((Number) afterSecond.get("totalQuestions")).intValue()).isEqualTo(2);
        assertThat((Boolean) afterSecond.get("complete")).isTrue();
        assertThat(questions.countBySessionId(sessionId)).isEqualTo(2);
    }

    // ---- refresh idempotency ----------------------------------------------------

    @Test
    void refreshingDoesNotDuplicateAdaptiveQuestions() throws Exception {
        Long sessionId = start(newInterview(3));

        questionsOf(sessionId);
        questionsOf(sessionId);
        questionsOf(sessionId);

        assertThat(questions.countBySessionId(sessionId)).isEqualTo(1);
    }

    // ---- fallback within adaptive mode -------------------------------------------

    @Test
    void fallbackStillWorksInAdaptiveMode() throws Exception {
        // ai-mode is MOCK (the test-profile default, unchanged by this class),
        // so every adaptively generated question here is fallback-sourced.
        Long sessionId = start(newInterview(2));
        var q1 = questionsOf(sessionId).get(0);
        answer(sessionId, q1.get("id"));

        List<Question> saved = questions.findBySessionIdOrderBySequenceNoAsc(sessionId);
        assertThat(saved).hasSize(2);
        assertThat(saved).allSatisfy(q -> {
            assertThat(q.getSource()).isEqualTo(QuestionSource.BANK);
            assertThat(q.getModelName()).isEqualTo("offline-fallback");
        });
        // And distinct, not the fallback selector's first pick repeated.
        assertThat(saved).extracting(Question::getText).doesNotHaveDuplicates();
    }

    // ---- FIXED mode is a different context; see QuestionModeIntegrationTest ----
    // ---- concurrency and prior-answer-context (real Gemini call captured):
    //      see AdaptiveGeminiIntegrationTest, which needs a mocked
    //      GeminiLlmClient neither this class nor its MOCK ai-mode has.
}
