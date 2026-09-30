package com.project.proctorinterview.interview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
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

import com.project.proctorinterview.TestDatabaseCleaner;
import com.project.proctorinterview.answer.AnswerRepository;
import com.project.proctorinterview.common.ApiException;
import com.project.proctorinterview.common.Enums.CandidateType;
import com.project.proctorinterview.common.Enums.CompletionReason;
import com.project.proctorinterview.common.Enums.InterviewStatus;
import com.project.proctorinterview.common.Enums.InterviewType;
import com.project.proctorinterview.common.Enums.Role;
import com.project.proctorinterview.common.Enums.SessionStatus;
import com.project.proctorinterview.interview.dto.InterviewDtos.CreateInterviewRequest;
import com.project.proctorinterview.report.ReportService;
import com.project.proctorinterview.user.User;
import com.project.proctorinterview.user.UserRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * Test duration as an actual rule of the interview.
 *
 * <p>The guarantees these tests exist for:
 *
 * <ul>
 *   <li>the clock starts when the candidate <b>starts</b>, not when the
 *       interview was scheduled;</li>
 *   <li>a refresh resumes the real remaining time rather than restarting it;</li>
 *   <li>the <b>server</b> decides whether time has run out - a browser that
 *       thinks it still has a minute left is overruled;</li>
 *   <li>expiry never loses an answer that was already submitted.</li>
 * </ul>
 *
 * <p>Expiry is simulated by moving a session's {@code startedAt} into the past
 * rather than by sleeping: the deadline is derived from it, so backdating the
 * start is exactly equivalent to time having passed, and the suite stays fast.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InterviewDurationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private TestDatabaseCleaner cleaner;
    @Autowired
    private UserRepository users;
    @Autowired
    private InterviewRepository interviews;
    @Autowired
    private InterviewSessionRepository sessions;
    @Autowired
    private AnswerRepository answers;
    @Autowired
    private InterviewService interviewService;
    @Autowired
    private ReportService reportService;
    @Autowired
    private InterviewExpirySweeper sweeper;
    @Autowired
    private PasswordEncoder encoder;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private org.springframework.transaction.support.TransactionTemplate transactions;

    private Long recruiterId;
    private Long candidateId;
    private String candidateJwt;

    @BeforeEach
    void setUp() throws Exception {
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

    private String login(String email, String password) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
                .andReturn().getResponse().getContentAsString();
        return (String) json.readValue(body, Map.class).get("token");
    }

    private Interview schedule(int durationMinutes) {
        CreateInterviewRequest req = new CreateInterviewRequest();
        req.setInterviewName("Java Screening");
        req.setCandidateId(candidateId);
        req.setScheduledAt(LocalDateTime.now().plusDays(1));
        req.setCandidateType(CandidateType.FRESHER);
        req.setDomain("Java");
        req.setInterviewType(InterviewType.TECHNICAL);
        req.setQuestionCount(3);
        req.setDurationMinutes(durationMinutes);
        return interviewService.create(recruiterId, req);
    }

    /** Starts the interview through the real endpoint and returns the session id. */
    private Long start(String token) throws Exception {
        String body = mvc.perform(post("/api/exam/{t}/start", token)
                        .header("Authorization", "Bearer " + candidateJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cameraGranted\":true,\"micGranted\":true,\"browserInfo\":\"test\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return ((Number) json.readValue(body, Map.class).get("sessionId")).longValue();
    }

    /**
     * Moves a session's start into the past, so its derived deadline has either
     * passed or is close. Equivalent to time elapsing, without a sleep.
     */
    private void backdateStart(Long sessionId, Duration by) {
        InterviewSession session = sessions.findById(sessionId).orElseThrow();
        session.setStartedAt(session.getStartedAt().minus(by));
        sessions.save(session);
    }

    /**
     * Reads a session with its interview resolved.
     *
     * <p>{@code deadline()} reads the interview's duration through a lazy
     * association, and open-in-view is off, so a plain {@code findById} outside
     * a transaction throws. Production callers reach it through
     * {@code requireOwnedSession}, which dereferences the interview first.
     */
    private InterviewSession loadSession(Long sessionId) {
        return transactions.execute(status -> {
            InterviewSession session = sessions.findById(sessionId).orElseThrow();
            session.getInterview().getDurationMinutes(); // force initialisation
            return session;
        });
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
                .content("{\"questionId\":%s,\"rawTranscript\":\"I would measure it first and then optimise.\",\"durationSeconds\":40}"
                        .formatted(questionId)));
    }

    // ---- the duration is configuration -------------------------------------

    @Test
    void singleSchedulingStoresTheConfiguredDuration() {
        Interview interview = schedule(45);
        assertThat(interviews.findById(interview.getId()).orElseThrow().getDurationMinutes())
                .isEqualTo(45);
    }

    @Test
    void aDurationOutsideTheAllowedRangeIsRefused() {
        assertThatDurationRejected(0);
        assertThatDurationRejected(181);
        assertThatDurationRejected(null);
    }

    private void assertThatDurationRejected(Integer minutes) {
        CreateInterviewRequest req = new CreateInterviewRequest();
        req.setInterviewName("Bad duration");
        req.setCandidateId(candidateId);
        req.setScheduledAt(LocalDateTime.now().plusDays(1));
        req.setCandidateType(CandidateType.FRESHER);
        req.setDomain("Java");
        req.setInterviewType(InterviewType.TECHNICAL);
        req.setQuestionCount(3);
        req.setDurationMinutes(minutes);

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> interviewService.create(recruiterId, req))
                .isInstanceOf(ApiException.class);
    }

    // ---- the clock starts when the candidate does ---------------------------

    @Test
    void theClockStartsWhenTheCandidateStartsNotAtTheScheduledTime() throws Exception {
        // Scheduled for tomorrow, but started now.
        Interview interview = schedule(30);
        Long sessionId = start(interview.getInviteToken());

        InterviewSession session = loadSession(sessionId);

        // Started now, not at the scheduled time a day away.
        assertThat(session.getStartedAt()).isBefore(Instant.now().plusSeconds(5));
        assertThat(session.getStartedAt()).isAfter(Instant.now().minusSeconds(60));

        // The deadline hangs off the real start.
        assertThat(session.deadline())
                .isEqualTo(session.getStartedAt().plus(Duration.ofMinutes(30)));

        // And is therefore before the scheduled time, which is a day away -
        // proof the allocation is not being measured from scheduledAt.
        assertThat(session.deadline()).isBefore(interview.getScheduledAt());
    }

    @Test
    void startingReportsTheFullDurationAsRemaining() throws Exception {
        Interview interview = schedule(30);

        String body = mvc.perform(post("/api/exam/{t}/start", interview.getInviteToken())
                        .header("Authorization", "Bearer " + candidateJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cameraGranted\":true,\"micGranted\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.durationMinutes").value(30))
                .andReturn().getResponse().getContentAsString();

        long remaining = ((Number) json.readValue(body, Map.class).get("remainingSeconds")).longValue();
        assertThat(remaining).isBetween(30 * 60 - 10L, 30L * 60);
    }

    // ---- refresh ------------------------------------------------------------

    /**
     * The behaviour the whole design exists for: reloading must not hand out a
     * fresh allocation of time.
     */
    @Test
    void refreshingResumesTheRemainingTimeRatherThanRestartingIt() throws Exception {
        Interview interview = schedule(30);
        Long sessionId = start(interview.getInviteToken());

        // Five minutes pass.
        backdateStart(sessionId, Duration.ofMinutes(5));

        // The candidate reloads: same session, and ~25 minutes left, not 30.
        String body = mvc.perform(post("/api/exam/{t}/start", interview.getInviteToken())
                        .header("Authorization", "Bearer " + candidateJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cameraGranted\":true,\"micGranted\":true}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Map<?, ?> parsed = json.readValue(body, Map.class);
        assertThat(((Number) parsed.get("sessionId")).longValue()).isEqualTo(sessionId);

        long remaining = ((Number) parsed.get("remainingSeconds")).longValue();
        assertThat(remaining).isBetween(24L * 60, 25L * 60);
    }

    @Test
    void theQuestionsEndpointAlsoReportsTheRemainingTime() throws Exception {
        Interview interview = schedule(30);
        Long sessionId = start(interview.getInviteToken());
        backdateStart(sessionId, Duration.ofMinutes(10));

        mvc.perform(get("/api/interview-sessions/{id}/questions", sessionId)
                        .header("Authorization", "Bearer " + candidateJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remainingSeconds").value(
                        org.hamcrest.Matchers.lessThanOrEqualTo(20 * 60)));
    }

    // ---- the backend is the authority --------------------------------------

    @Test
    void anAnswerSubmittedAfterTheDurationHasRunOutIsRejected() throws Exception {
        Interview interview = schedule(10);
        Long sessionId = start(interview.getInviteToken());
        var list = questionsOf(sessionId);

        // One answer lands in time.
        answer(sessionId, list.get(0).get("id")).andExpect(status().isOk());

        // Time runs out.
        backdateStart(sessionId, Duration.ofMinutes(11));

        // The browser might still show a minute left; the server does not care.
        answer(sessionId, list.get(1).get("id")).andExpect(status().isConflict());

        // The answer submitted before expiry is untouched.
        assertThat(answers.findByQuestionSessionIdOrderByQuestionSequenceNoAsc(sessionId)).hasSize(1);
    }

    @Test
    void anExpiredSessionIsClosedAndRecordedAsTimeExpired() throws Exception {
        Interview interview = schedule(10);
        Long sessionId = start(interview.getInviteToken());
        var list = questionsOf(sessionId);
        backdateStart(sessionId, Duration.ofMinutes(11));

        // The attempted write is what trips the expiry check.
        answer(sessionId, list.get(0).get("id")).andExpect(status().isConflict());

        InterviewSession session = loadSession(sessionId);
        assertThat(session.getStatus()).isEqualTo(SessionStatus.COMPLETED);
        assertThat(session.getCompletionReason()).isEqualTo(CompletionReason.TIME_EXPIRED);
        // Ended at the deadline, not at whenever expiry happened to be noticed.
        assertThat(session.getEndedAt()).isEqualTo(session.deadline());
    }

    @Test
    void anExpiredInterviewCannotBeRestarted() throws Exception {
        Interview interview = schedule(10);
        Long sessionId = start(interview.getInviteToken());
        backdateStart(sessionId, Duration.ofMinutes(11));

        mvc.perform(post("/api/exam/{t}/start", interview.getInviteToken())
                        .header("Authorization", "Bearer " + candidateJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cameraGranted\":true,\"micGranted\":true}"))
                .andExpect(status().isConflict());
    }

    // ---- completion reasons -------------------------------------------------

    @Test
    void finishingEarlyIsRecordedAsCandidateFinished() throws Exception {
        Interview interview = schedule(30);
        Long sessionId = start(interview.getInviteToken());
        questionsOf(sessionId);

        mvc.perform(post("/api/interview-sessions/{id}/complete", sessionId)
                .header("Authorization", "Bearer " + candidateJwt)).andExpect(status().isOk());

        InterviewSession session = sessions.findById(sessionId).orElseThrow();
        assertThat(session.getCompletionReason()).isEqualTo(CompletionReason.CANDIDATE_FINISHED);
        assertThat(session.getStatus()).isEqualTo(SessionStatus.COMPLETED);
        assertThat(interviews.findById(interview.getId()).orElseThrow().getStatus())
                .isEqualTo(InterviewStatus.COMPLETED);
    }

    /**
     * Completing after the deadline is recorded as expiry even though the
     * request is the same one the Finish button sends - the candidate ran out of
     * time, they did not choose to stop.
     */
    @Test
    void completingAfterTheDeadlineIsRecordedAsTimeExpired() throws Exception {
        Interview interview = schedule(10);
        Long sessionId = start(interview.getInviteToken());
        questionsOf(sessionId);
        backdateStart(sessionId, Duration.ofMinutes(11));

        mvc.perform(post("/api/interview-sessions/{id}/complete", sessionId)
                .header("Authorization", "Bearer " + candidateJwt)).andExpect(status().isOk());

        assertThat(sessions.findById(sessionId).orElseThrow().getCompletionReason())
                .isEqualTo(CompletionReason.TIME_EXPIRED);
    }

    // ---- the report ---------------------------------------------------------

    @Test
    void theReportStatesAllocatedTimeActualTimeAndWhyItEnded() throws Exception {
        Interview interview = schedule(30);
        Long sessionId = start(interview.getInviteToken());
        var list = questionsOf(sessionId);
        answer(sessionId, list.get(0).get("id")).andExpect(status().isOk());

        // Ten minutes of interview, then the candidate finishes.
        backdateStart(sessionId, Duration.ofMinutes(10));
        mvc.perform(post("/api/interview-sessions/{id}/complete", sessionId)
                .header("Authorization", "Bearer " + candidateJwt)).andExpect(status().isOk());

        var timing = reportService.view(sessionId).timing();

        assertThat(timing.allocatedMinutes()).isEqualTo(30);
        assertThat(timing.allocatedText()).isEqualTo("30 minutes");
        assertThat(timing.completionReason()).isEqualTo(CompletionReason.CANDIDATE_FINISHED);
        assertThat(timing.completionText()).isEqualTo("Candidate finished");

        // Roughly the ten minutes that actually elapsed, not the allocated 30.
        assertThat(timing.actualSeconds()).isBetween(9L * 60, 11L * 60);
        assertThat(timing.overran()).isFalse();

        // One of three answered; the other two are unanswered, not "skipped".
        assertThat(timing.answeredCount()).isEqualTo(1);
        assertThat(timing.totalQuestions()).isEqualTo(3);
        assertThat(timing.unansweredCount()).isEqualTo(2);

        // Per-answer figures come from the reported durations.
        assertThat(timing.averageAnswerText()).isEqualTo("40 s");
        assertThat(timing.longestAnswerText()).isEqualTo("40 s");
        assertThat(timing.shortestAnswerText()).isEqualTo("40 s");
    }

    @Test
    void aReportWithNoAnswersHasNoPerAnswerAveragesRatherThanZeroes() throws Exception {
        Interview interview = schedule(30);
        Long sessionId = start(interview.getInviteToken());
        questionsOf(sessionId);

        mvc.perform(post("/api/interview-sessions/{id}/complete", sessionId)
                .header("Authorization", "Bearer " + candidateJwt)).andExpect(status().isOk());

        var timing = reportService.view(sessionId).timing();
        assertThat(timing.answeredCount()).isZero();
        // Absent, not 0 - nobody answered anything, so there is no average.
        assertThat(timing.averageAnswerText()).isNull();
        assertThat(timing.longestAnswerText()).isNull();
    }

    @Test
    void expiryPreservesSubmittedAnswersAndTheReportCountsTheRestUnanswered() throws Exception {
        Interview interview = schedule(10);
        Long sessionId = start(interview.getInviteToken());
        var list = questionsOf(sessionId);

        answer(sessionId, list.get(0).get("id")).andExpect(status().isOk());
        backdateStart(sessionId, Duration.ofMinutes(11));

        mvc.perform(post("/api/interview-sessions/{id}/complete", sessionId)
                .header("Authorization", "Bearer " + candidateJwt)).andExpect(status().isOk());

        var timing = reportService.view(sessionId).timing();
        assertThat(timing.completionReason()).isEqualTo(CompletionReason.TIME_EXPIRED);
        assertThat(timing.answeredCount()).isEqualTo(1);
        assertThat(timing.unansweredCount()).isEqualTo(2);

        // The submitted answer survived.
        assertThat(answers.findByQuestionSessionIdOrderByQuestionSequenceNoAsc(sessionId)).hasSize(1);
    }

    // ---- the exam info the candidate's browser reads ------------------------

    @Test
    void theExamInfoCarriesTheConfiguredDuration() throws Exception {
        Interview interview = schedule(20);

        mvc.perform(get("/api/exam/{t}", interview.getInviteToken())
                        .header("Authorization", "Bearer " + candidateJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.durationMinutes").value(20));
    }

    // ---- the abandoned case: nobody is left to finalise it -----------------

    /**
     * The invariant the sweeper exists for: an interview must not still be
     * running after its deadline, even when the candidate simply closed the
     * laptop and no request ever arrives to notice.
     */
    @Test
    void anAbandonedInterviewIsFinalisedBySweepWithoutAnyRequestFromTheCandidate() throws Exception {
        Interview interview = schedule(10);
        Long sessionId = start(interview.getInviteToken());
        var list = questionsOf(sessionId);
        answer(sessionId, list.get(0).get("id")).andExpect(status().isOk());

        // The candidate closes the browser. Nothing else happens.
        backdateStart(sessionId, Duration.ofMinutes(11));

        assertThat(sweeper.expiredSessionIds()).contains(sessionId);
        sweeper.closeExpiredInterviews();

        InterviewSession session = loadSession(sessionId);
        assertThat(session.getStatus()).isEqualTo(SessionStatus.COMPLETED);
        assertThat(session.getCompletionReason()).isEqualTo(CompletionReason.TIME_EXPIRED);
        assertThat(session.getEndedAt()).isEqualTo(session.deadline());

        // The interview is no longer running, so a recruiter sees it as done.
        assertThat(interviews.findById(interview.getId()).orElseThrow().getStatus())
                .isEqualTo(InterviewStatus.COMPLETED);

        // It went through the ordinary completion flow, so the report exists
        // and the answer that was given survived.
        var timing = reportService.view(sessionId).timing();
        assertThat(timing.completionReason()).isEqualTo(CompletionReason.TIME_EXPIRED);
        assertThat(timing.answeredCount()).isEqualTo(1);
        assertThat(answers.findByQuestionSessionIdOrderByQuestionSequenceNoAsc(sessionId)).hasSize(1);
    }

    @Test
    void theSweepLeavesInterviewsThatAreStillWithinTheirTimeAlone() throws Exception {
        Interview interview = schedule(30);
        Long sessionId = start(interview.getInviteToken());
        backdateStart(sessionId, Duration.ofMinutes(5)); // 25 still to go

        assertThat(sweeper.expiredSessionIds()).doesNotContain(sessionId);
        sweeper.closeExpiredInterviews();

        assertThat(loadSession(sessionId).getStatus()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(interviews.findById(interview.getId()).orElseThrow().getStatus())
                .isEqualTo(InterviewStatus.IN_PROGRESS);
    }

    /** Sweeping twice must not finalise twice, or relabel the first outcome. */
    @Test
    void aSecondSweepChangesNothing() throws Exception {
        Interview interview = schedule(10);
        Long sessionId = start(interview.getInviteToken());
        questionsOf(sessionId);
        backdateStart(sessionId, Duration.ofMinutes(11));

        sweeper.closeExpiredInterviews();
        var firstGeneratedAt = reportService.view(sessionId).generatedAt();

        sweeper.closeExpiredInterviews();

        assertThat(reportService.view(sessionId).generatedAt()).isEqualTo(firstGeneratedAt);
        assertThat(loadSession(sessionId).getCompletionReason())
                .isEqualTo(CompletionReason.TIME_EXPIRED);
    }

    /**
     * A candidate who finished in time and then gets swept keeps their own
     * reason: the sweep must not relabel a voluntary finish as an expiry.
     */
    @Test
    void theSweepNeverRelabelsAnInterviewTheCandidateAlreadyFinished() throws Exception {
        Interview interview = schedule(10);
        Long sessionId = start(interview.getInviteToken());
        questionsOf(sessionId);

        mvc.perform(post("/api/interview-sessions/{id}/complete", sessionId)
                .header("Authorization", "Bearer " + candidateJwt)).andExpect(status().isOk());

        // Time passes well beyond the deadline; the sweep runs.
        backdateStart(sessionId, Duration.ofMinutes(11));
        sweeper.closeExpiredInterviews();

        assertThat(loadSession(sessionId).getCompletionReason())
                .isEqualTo(CompletionReason.CANDIDATE_FINISHED);
    }

    // ---- what the candidate is told -----------------------------------------

    /** The closing screen needs the reason to say time ran out. */
    @Test
    void theCompletionResponseCarriesTheReason() throws Exception {
        Interview interview = schedule(10);
        Long sessionId = start(interview.getInviteToken());
        questionsOf(sessionId);
        backdateStart(sessionId, Duration.ofMinutes(11));

        mvc.perform(post("/api/interview-sessions/{id}/complete", sessionId)
                        .header("Authorization", "Bearer " + candidateJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completionReason").value("TIME_EXPIRED"));
    }

    @Test
    void finishingInTimeReportsCandidateFinishedToTheBrowser() throws Exception {
        Interview interview = schedule(30);
        Long sessionId = start(interview.getInviteToken());
        questionsOf(sessionId);

        mvc.perform(post("/api/interview-sessions/{id}/complete", sessionId)
                        .header("Authorization", "Bearer " + candidateJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completionReason").value("CANDIDATE_FINISHED"));
    }

    // ---- helper behaviour on the entity ------------------------------------

    @Test
    void remainingTimeIsFlooredAtZeroRatherThanGoingNegative() throws Exception {
        Interview interview = schedule(10);
        Long sessionId = start(interview.getInviteToken());
        backdateStart(sessionId, Duration.ofHours(3));

        InterviewSession session = loadSession(sessionId);
        assertThat(session.remainingSecondsAt(Instant.now())).isZero();
        assertThat(session.hasExpiredAt(Instant.now())).isTrue();
    }
}
