package com.project.proctorinterview.interview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.project.proctorinterview.answer.AnswerRepository;
import com.project.proctorinterview.common.Enums.CandidateType;
import com.project.proctorinterview.common.Enums.InterviewType;
import com.project.proctorinterview.common.Enums.Role;
import com.project.proctorinterview.interview.dto.InterviewDtos.CreateInterviewRequest;
import com.project.proctorinterview.proctor.ProctorEventRepository;
import com.project.proctorinterview.question.QuestionRepository;
import com.project.proctorinterview.report.ReportRepository;
import com.project.proctorinterview.user.User;
import com.project.proctorinterview.user.UserRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * The states a real candidate can actually get into: reloading, answering
 * twice, arriving after cancellation, or coming back once it is over.
 *
 * <p>Each of these should give a clear, correct response rather than a 500 or a
 * silently corrupted session.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SessionLifecycleTest {

    @Autowired
    private com.project.proctorinterview.TestDatabaseCleaner cleaner;

    @Autowired
    private MockMvc mvc;
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
    private ProctorEventRepository events;
    @Autowired
    private ReportRepository reports;
    @Autowired
    private InterviewService interviewService;
    @Autowired
    private PasswordEncoder encoder;
    @Autowired
    private ObjectMapper json;

    private String candidateJwt;
    private Long recruiterId;
    private Long candidateId;
    private String inviteToken;

    @BeforeEach
    void setUp() throws Exception {
        cleaner.clean();

        recruiterId = user("iv@test.local", "Recruiter@1", Role.RECRUITER);
        candidateId = user("cand@test.local", "Candidate@1", Role.CANDIDATE);
        inviteToken = newInterview();
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

    private String newInterview() {
        CreateInterviewRequest req = new CreateInterviewRequest();

        req.setCandidateId(candidateId);
        req.setScheduledAt(LocalDateTime.now().plusDays(1));
        req.setCandidateType(CandidateType.FRESHER);
        req.setDomain("Java");
        req.setInterviewType(InterviewType.TECHNICAL);
        req.setQuestionCount(3);
        return interviewService.create(recruiterId, req).getInviteToken();
    }

    private String login(String email, String password) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
                .andReturn().getResponse().getContentAsString();
        return (String) json.readValue(body, Map.class).get("token");
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

    private java.util.List<Map<String, Object>> questionsOf(Long sessionId) throws Exception {
        String body = mvc.perform(get("/api/interview-sessions/{id}/questions", sessionId)
                        .header("Authorization", "Bearer " + candidateJwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        @SuppressWarnings("unchecked")
        var list = (java.util.List<Map<String, Object>>) json.readValue(body, Map.class).get("questions");
        return list;
    }

    private org.springframework.test.web.servlet.ResultActions answer(Long sessionId, Object questionId)
            throws Exception {
        return mvc.perform(post("/api/interview-sessions/{id}/answers", sessionId)
                .header("Authorization", "Bearer " + candidateJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"questionId\":%s,\"rawTranscript\":\"I would use a transaction and measure first.\",\"durationSeconds\":30}"
                        .formatted(questionId)));
    }

    // ---- reloading ----------------------------------------------------------

    @Test
    void reloadingMidInterviewKeepsTheSameSessionAndQuestions() throws Exception {
        Long sessionId = start(inviteToken);
        var first = questionsOf(sessionId);

        // Same as the browser being refreshed.
        assertThat(start(inviteToken)).isEqualTo(sessionId);
        var second = questionsOf(sessionId);

        assertThat(second).hasSameSizeAs(first);
        assertThat(second.get(0).get("id")).isEqualTo(first.get(0).get("id"));
        assertThat(second.get(0).get("text")).isEqualTo(first.get(0).get("text"));
    }

    @Test
    void questionsAreGeneratedOnceNotOnEveryRequest() throws Exception {
        Long sessionId = start(inviteToken);
        questionsOf(sessionId);
        questionsOf(sessionId);
        questionsOf(sessionId);

        assertThat(questions.countBySessionId(sessionId)).isEqualTo(3);
    }

    @Test
    void answeredQuestionsAreMarkedSoTheUiCanResume() throws Exception {
        Long sessionId = start(inviteToken);
        var list = questionsOf(sessionId);
        answer(sessionId, list.get(0).get("id")).andExpect(status().isOk());

        var reloaded = questionsOf(sessionId);
        assertThat(reloaded.get(0).get("answered")).isEqualTo(true);
        assertThat(reloaded.get(1).get("answered")).isEqualTo(false);
    }

    // ---- answering ----------------------------------------------------------

    @Test
    void thesameQuestionCannotBeAnsweredTwice() throws Exception {
        Long sessionId = start(inviteToken);
        var list = questionsOf(sessionId);

        answer(sessionId, list.get(0).get("id")).andExpect(status().isOk());
        // Re-answering would let a candidate revise after seeing later questions.
        answer(sessionId, list.get(0).get("id")).andExpect(status().isConflict());

        assertThat(answers.findByQuestionSessionIdOrderByQuestionSequenceNoAsc(sessionId)).hasSize(1);
    }

    @Test
    void aQuestionFromAnotherSessionIsRejected() throws Exception {
        Long sessionId = start(inviteToken);
        var mine = questionsOf(sessionId);

        // A second interview for the same candidate, with its own questions.
        String otherToken = newInterview();
        Long otherSession = start(otherToken);
        var theirs = questionsOf(otherSession);

        answer(sessionId, theirs.get(0).get("id")).andExpect(status().isForbidden());
        assertThat(mine).isNotEmpty();
    }

    @Test
    void anUnknownQuestionIdIsNotFound() throws Exception {
        Long sessionId = start(inviteToken);
        questionsOf(sessionId);

        answer(sessionId, 999999).andExpect(status().isNotFound());
    }

    @Test
    void anEmptyAnswerIsAcceptedAndScoredAsUnanswered() throws Exception {
        // A candidate who genuinely has nothing to say should not be blocked.
        Long sessionId = start(inviteToken);
        var list = questionsOf(sessionId);

        mvc.perform(post("/api/interview-sessions/{id}/answers", sessionId)
                        .header("Authorization", "Bearer " + candidateJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questionId\":%s,\"rawTranscript\":\"\",\"durationSeconds\":5}"
                                .formatted(list.get(0).get("id"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.wordCount").value(0));
    }

    // ---- completion ---------------------------------------------------------

    @Test
    void completingWithUnansweredQuestionsStillProducesAReport() throws Exception {
        Long sessionId = start(inviteToken);
        var list = questionsOf(sessionId);
        answer(sessionId, list.get(0).get("id")).andExpect(status().isOk());

        // The candidate gives up after one of three.
        mvc.perform(post("/api/interview-sessions/{id}/complete", sessionId)
                        .header("Authorization", "Bearer " + candidateJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completed").value(true));

        assertThat(reports.existsBySessionId(sessionId)).isTrue();
    }

    @Test
    void answeringAfterCompletionIsRejected() throws Exception {
        Long sessionId = start(inviteToken);
        var list = questionsOf(sessionId);
        mvc.perform(post("/api/interview-sessions/{id}/complete", sessionId)
                .header("Authorization", "Bearer " + candidateJwt)).andExpect(status().isOk());

        answer(sessionId, list.get(0).get("id")).andExpect(status().isConflict());
    }

    @Test
    void completingTwiceReturnsTheSameReportRatherThanRegenerating() throws Exception {
        Long sessionId = start(inviteToken);
        questionsOf(sessionId);

        mvc.perform(post("/api/interview-sessions/{id}/complete", sessionId)
                .header("Authorization", "Bearer " + candidateJwt)).andExpect(status().isOk());
        var generatedAt = reports.findBySessionId(sessionId).orElseThrow().getGeneratedAt();

        mvc.perform(post("/api/interview-sessions/{id}/complete", sessionId)
                .header("Authorization", "Bearer " + candidateJwt)).andExpect(status().isOk());

        assertThat(reports.findBySessionId(sessionId).orElseThrow().getGeneratedAt())
                .isEqualTo(generatedAt);
        assertThat(reports.findAll()).hasSize(1);
    }

    @Test
    void restartingAfterCompletionIsRejected() throws Exception {
        Long sessionId = start(inviteToken);
        mvc.perform(post("/api/interview-sessions/{id}/complete", sessionId)
                .header("Authorization", "Bearer " + candidateJwt)).andExpect(status().isOk());

        mvc.perform(post("/api/exam/{t}/start", inviteToken)
                        .header("Authorization", "Bearer " + candidateJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cameraGranted\":true,\"micGranted\":true}"))
                .andExpect(status().isConflict());
    }

    // ---- cancelled interview ------------------------------------------------

    @Test
    void aCancelledInterviewCannotBeStarted() throws Exception {
        interviewService.cancel(
                interviews.findByInviteToken(inviteToken).orElseThrow().getId(),
                recruiterId, Role.RECRUITER);

        mvc.perform(post("/api/exam/{t}/start", inviteToken)
                        .header("Authorization", "Bearer " + candidateJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cameraGranted\":true,\"micGranted\":true}"))
                .andExpect(status().isConflict());

        assertThat(sessions.findByInterviewInviteToken(inviteToken)).isEmpty();
    }

    /**
     * The interview mode reaches the browser, and is NORMAL unless DEMO was
     * explicitly configured.
     *
     * <p>The default matters more than the plumbing: a deployment that forgets
     * to set anything must give a real candidate the clean interview, never the
     * monitoring panel.
     */
    @Test
    void examInfoCarriesTheInterviewModeAndDefaultsToNormal() throws Exception {
        mvc.perform(get("/api/exam/{t}", inviteToken)
                        .header("Authorization", "Bearer " + candidateJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.interviewMode").value("NORMAL"));
    }

    @Test
    void anUnknownInviteTokenIsNotFound() throws Exception {
        mvc.perform(get("/api/exam/{t}", UUID.randomUUID().toString())
                        .header("Authorization", "Bearer " + candidateJwt))
                .andExpect(status().isNotFound());
    }

    // ---- proctor events across the lifecycle --------------------------------

    @Test
    void observationsCanStillArriveAfterCompletion() throws Exception {
        // The browser flushes its queue as the session closes, so a late batch
        // must be accepted rather than lost.
        Long sessionId = start(inviteToken);
        mvc.perform(post("/api/interview-sessions/{id}/complete", sessionId)
                .header("Authorization", "Bearer " + candidateJwt)).andExpect(status().isOk());

        mvc.perform(post("/api/interview-sessions/{id}/proctor-events", sessionId)
                        .header("Authorization", "Bearer " + candidateJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"events":[{"clientEventId":"late-event-0001","type":"NO_FACE",
                                "startTime":"2026-08-15T10:00:00Z","endTime":"2026-08-15T10:00:05Z",
                                "durationMs":5000}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(1));
    }
}
