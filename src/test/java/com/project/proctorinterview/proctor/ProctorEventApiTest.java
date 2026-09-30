package com.project.proctorinterview.proctor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
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

import com.project.proctorinterview.common.Enums.CandidateType;
import com.project.proctorinterview.common.Enums.InterviewType;
import com.project.proctorinterview.common.Enums.MonitoringCoverage;
import com.project.proctorinterview.common.Enums.ProctorEventType;
import com.project.proctorinterview.common.Enums.Role;
import com.project.proctorinterview.interview.InterviewRepository;
import com.project.proctorinterview.proctor.dto.ProctorEventDtos.ProctorEventBatch;
import com.project.proctorinterview.interview.InterviewService;
import com.project.proctorinterview.interview.dto.InterviewDtos.CreateInterviewRequest;
import com.project.proctorinterview.user.User;
import com.project.proctorinterview.user.UserRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * The proctor-event ingest contract: ownership, validation, and above all
 * idempotency - the browser retries batches, and a retry must never duplicate
 * an observation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProctorEventApiTest {

    @Autowired
    private com.project.proctorinterview.TestDatabaseCleaner cleaner;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UserRepository users;
    @Autowired
    private InterviewRepository interviews;
    @Autowired
    private ProctorEventRepository events;
    @Autowired
    private ProctorEventRetryService retryService;
    @Autowired
    private com.project.proctorinterview.interview.InterviewSessionRepository sessions;
    @Autowired
    private InterviewService interviewService;
    @Autowired
    private PasswordEncoder encoder;
    @Autowired
    private ObjectMapper json;

    private String candidateToken;
    private String otherCandidateToken;
    private String inviteToken;
    private Long sessionId;

    @BeforeEach
    void setUp() throws Exception {
        cleaner.clean();

        Long recruiterId = user("iv@test.local", "Recruiter@1", Role.RECRUITER);
        Long candidateId = user("cand@test.local", "Candidate@1", Role.CANDIDATE);
        user("other@test.local", "Other@12345", Role.CANDIDATE);

        CreateInterviewRequest req = new CreateInterviewRequest();

        req.setCandidateId(candidateId);
        req.setScheduledAt(LocalDateTime.now().plusDays(1));
        req.setCandidateType(CandidateType.FRESHER);
        req.setDomain("Java");
        req.setInterviewType(InterviewType.TECHNICAL);
        req.setQuestionCount(5);
        inviteToken = interviewService.create(recruiterId, req).getInviteToken();

        candidateToken = login("cand@test.local", "Candidate@1");
        otherCandidateToken = login("other@test.local", "Other@12345");
        sessionId = startExam(candidateToken);
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
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return (String) json.readValue(body, Map.class).get("token");
    }

    private Long startExam(String token) throws Exception {
        String body = mvc.perform(post("/api/exam/{t}/start", inviteToken)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cameraGranted\":true,\"micGranted\":true,\"browserInfo\":\"test\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return ((Number) json.readValue(body, Map.class).get("sessionId")).longValue();
    }

    /**
     * Builds a batch. The short names are expanded to realistic client ids -
     * the real client sends UUIDs, and ids shorter than 8 characters are
     * rejected by validation.
     */
    private String batchJson(String... clientEventIds) {
        List<Map<String, Object>> list = java.util.Arrays.stream(clientEventIds)
                .map(id -> Map.<String, Object>of(
                        "clientEventId", "client-event-" + id,
                        "type", "PHONE_DETECTED",
                        "startTime", Instant.parse("2026-08-15T10:00:00Z").toString(),
                        "endTime", Instant.parse("2026-08-15T10:00:05Z").toString(),
                        "durationMs", 5000,
                        "confidence", 0.91,
                        "details", Map.of("bbox", List.of(220, 140, 90, 180))))
                .toList();
        return json.writeValueAsString(Map.of("events", list));
    }

    private org.springframework.test.web.servlet.ResultActions upload(String token, String body) throws Exception {
        return mvc.perform(post("/api/interview-sessions/{id}/proctor-events", sessionId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private org.springframework.test.web.servlet.ResultActions reportMonitoring(
            String token, String body) throws Exception {
        return mvc.perform(post("/api/interview-sessions/{id}/monitoring-status", sessionId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    // ---- ingest ----------------------------------------------------------

    @Test
    void storesABatchOfEvents() throws Exception {
        upload(candidateToken, batchJson("evt-1", "evt-2", "evt-3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted", is(3)))
                .andExpect(jsonPath("$.duplicates", is(0)));

        assertThat(events.findBySessionIdOrderByStartTimeAsc(sessionId)).hasSize(3);
    }

    @Test
    void retryingTheSameBatchDoesNotDuplicateEvents() throws Exception {
        upload(candidateToken, batchJson("evt-1", "evt-2")).andExpect(jsonPath("$.accepted", is(2)));

        // Exactly what the browser does when the first response was lost.
        upload(candidateToken, batchJson("evt-1", "evt-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted", is(0)))
                .andExpect(jsonPath("$.duplicates", is(2)));

        assertThat(events.findBySessionIdOrderByStartTimeAsc(sessionId)).hasSize(2);
    }

    @Test
    void aPartiallyOverlappingRetryStoresOnlyTheNewEvents() throws Exception {
        upload(candidateToken, batchJson("evt-1", "evt-2")).andExpect(status().isOk());

        upload(candidateToken, batchJson("evt-2", "evt-3"))
                .andExpect(jsonPath("$.accepted", is(1)))
                .andExpect(jsonPath("$.duplicates", is(1)));

        assertThat(events.findBySessionIdOrderByStartTimeAsc(sessionId)).hasSize(3);
    }

    @Test
    void storedEventKeepsConfidenceDurationAndDetails() throws Exception {
        upload(candidateToken, batchJson("evt-1")).andExpect(status().isOk());

        ProctorEvent stored = events.findBySessionIdOrderByStartTimeAsc(sessionId).getFirst();
        assertThat(stored.getEventType()).isEqualTo(ProctorEventType.PHONE_DETECTED);
        assertThat(stored.getDurationMs()).isEqualTo(5000L);
        assertThat(stored.getConfidence()).isEqualByComparingTo("0.910");
        assertThat(stored.getDetails()).containsKey("bbox");
    }

    @Test
    void durationIsDerivedWhenTheClientOmitsIt() throws Exception {
        String body = json.writeValueAsString(Map.of("events", List.of(Map.of(
                "clientEventId", "no-duration",
                "type", "NO_FACE",
                "startTime", "2026-08-15T10:00:00Z",
                "endTime", "2026-08-15T10:00:04Z"))));

        upload(candidateToken, body).andExpect(status().isOk());

        assertThat(events.findBySessionIdOrderByStartTimeAsc(sessionId).getFirst().getDurationMs())
                .isEqualTo(4000L);
    }

    // ---- ownership -------------------------------------------------------

    @Test
    void anotherCandidateCannotWriteIntoThisSession() throws Exception {
        upload(otherCandidateToken, batchJson("evt-x"))
                .andExpect(status().isForbidden());

        assertThat(events.findBySessionIdOrderByStartTimeAsc(sessionId)).isEmpty();
    }

    @Test
    void anonymousUploadIsRejected() throws Exception {
        mvc.perform(post("/api/interview-sessions/{id}/proctor-events", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchJson("evt-anon")))
                .andExpect(status().isUnauthorized());

        assertThat(events.findBySessionIdOrderByStartTimeAsc(sessionId)).isEmpty();
    }

    // ---- validation ------------------------------------------------------

    @Test
    void emptyBatchIsRejected() throws Exception {
        upload(candidateToken, "{\"events\":[]}").andExpect(status().isBadRequest());
    }

    @Test
    void outOfRangeConfidenceIsRejected() throws Exception {
        String body = json.writeValueAsString(Map.of("events", List.of(Map.of(
                "clientEventId", "bad-confidence",
                "type", "PHONE_DETECTED",
                "startTime", "2026-08-15T10:00:00Z",
                "confidence", 1.7))));

        upload(candidateToken, body).andExpect(status().isBadRequest());
        assertThat(events.findBySessionIdOrderByStartTimeAsc(sessionId)).isEmpty();
    }

    @Test
    void missingClientEventIdIsRejected() throws Exception {
        String body = json.writeValueAsString(Map.of("events", List.of(Map.of(
                "type", "NO_FACE",
                "startTime", "2026-08-15T10:00:00Z"))));

        upload(candidateToken, body).andExpect(status().isBadRequest());
    }

    // ---- exam lifecycle --------------------------------------------------

    @Test
    void startingTwiceResumesTheSameSession() throws Exception {
        assertThat(startExam(candidateToken)).isEqualTo(sessionId);
    }

    @Test
    void wrongCandidateGetsNotFoundForSomeoneElsesLink() throws Exception {
        // 404 rather than 403: holding a stranger's link should not confirm it exists.
        mvc.perform(post("/api/exam/{t}/start", inviteToken)
                        .header("Authorization", "Bearer " + otherCandidateToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cameraGranted\":true,\"micGranted\":true}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void cameraIsRequiredToStart() throws Exception {
        // A second, not-yet-started interview for the same candidate.
        Long recruiterId = users.findByEmailIgnoreCase("iv@test.local").orElseThrow().getId();
        Long candidateId = users.findByEmailIgnoreCase("cand@test.local").orElseThrow().getId();

        CreateInterviewRequest req = new CreateInterviewRequest();

        req.setCandidateId(candidateId);
        req.setScheduledAt(LocalDateTime.now().plusDays(2));
        req.setCandidateType(CandidateType.FRESHER);
        req.setDomain("Python");
        req.setInterviewType(InterviewType.TECHNICAL);
        req.setQuestionCount(5);
        String secondToken = interviewService.create(recruiterId, req).getInviteToken();

        mvc.perform(post("/api/exam/{t}/start", secondToken)
                        .header("Authorization", "Bearer " + candidateToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cameraGranted\":false,\"micGranted\":true}"))
                .andExpect(status().isBadRequest());

        assertThat(sessions.findByInterviewInviteToken(secondToken)).isEmpty();
    }

    @Test
    void unknownInviteTokenIsNotFound() throws Exception {
        mvc.perform(post("/api/exam/{t}/start", UUID.randomUUID().toString())
                        .header("Authorization", "Bearer " + candidateToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cameraGranted\":true,\"micGranted\":true}"))
                .andExpect(status().isNotFound());
    }

    // ---- monitoring coverage ---------------------------------------------
    //
    // What these protect: a session with no observations used to be
    // indistinguishable from a session nobody watched, and the report read as
    // clean either way. Coverage is about the OBSERVER, never the candidate,
    // and it must never move a score.

    @Test
    void aFreshSessionHasNotYetConfirmedItsMonitoring() {
        // Not null - null means "predates coverage recording". A live session
        // that has not reported in is a different, and worse, statement.
        var session = sessions.findById(sessionId).orElseThrow();

        assertThat(session.getMonitoringReady()).isFalse();
        assertThat(session.getMonitoringDroppedEvents()).isZero();
        assertThat(session.coverageWith(0)).isEqualTo(MonitoringCoverage.INCOMPLETE);
    }

    @Test
    void confirmingTheModelsLoadedMakesAQuietSessionReadAsMonitored() throws Exception {
        reportMonitoring(candidateToken, "{\"ready\":true,\"droppedEvents\":0}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ready", is(true)))
                .andExpect(jsonPath("$.coverage", is("NONE_OBSERVED")));

        assertThat(sessions.findById(sessionId).orElseThrow().getMonitoringReady()).isTrue();
    }

    @Test
    void aMonitoringFailureIsRecordedWithTheBrowsersOwnReason() throws Exception {
        reportMonitoring(candidateToken,
                "{\"ready\":false,\"droppedEvents\":0,\"note\":\"Detection models failed to load: WebGL\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coverage", is("INCOMPLETE")));

        assertThat(sessions.findById(sessionId).orElseThrow().getMonitoringNote())
                .contains("failed to load");
    }

    @Test
    void droppedEventsMakeEvenARecordedSessionIncomplete() throws Exception {
        upload(candidateToken, batchJson("evt-1")).andExpect(status().isOk());
        reportMonitoring(candidateToken, "{\"ready\":true,\"droppedEvents\":3}")
                .andExpect(status().isOk())
                // Observations exist, but the record is knowingly partial, and
                // that outranks the count.
                .andExpect(jsonPath("$.coverage", is("INCOMPLETE")))
                .andExpect(jsonPath("$.droppedEvents", is(3)));
    }

    @Test
    void aLaterReportCannotEraseAnAlreadyRecordedLoss() throws Exception {
        reportMonitoring(candidateToken, "{\"ready\":true,\"droppedEvents\":5}").andExpect(status().isOk());

        // Reports are cumulative and can arrive out of order; the maximum wins
        // so a stale one never quietly restores a clean record.
        reportMonitoring(candidateToken, "{\"ready\":true,\"droppedEvents\":2}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.droppedEvents", is(5)));
    }

    @Test
    void modelsHavingLoadedIsNotUnsaidByALaterReport() throws Exception {
        reportMonitoring(candidateToken, "{\"ready\":true,\"droppedEvents\":0}").andExpect(status().isOk());

        // The pagehide report sends whatever it knows; it must not undo a fact.
        reportMonitoring(candidateToken, "{\"ready\":false,\"droppedEvents\":0}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ready", is(true)));
    }

    @Test
    void anotherCandidateCannotReportOnThisSessionsMonitoring() throws Exception {
        reportMonitoring(otherCandidateToken, "{\"ready\":false,\"droppedEvents\":99}")
                .andExpect(status().isForbidden());

        assertThat(sessions.findById(sessionId).orElseThrow().getMonitoringDroppedEvents()).isZero();
    }

    @Test
    void aNegativeDroppedCountIsRejected() throws Exception {
        reportMonitoring(candidateToken, "{\"ready\":true,\"droppedEvents\":-1}")
                .andExpect(status().isBadRequest());
    }

    // ---- idempotency under concurrency ------------------------------------
    //
    // The pre-read that skips known ids is not atomic with the insert. The
    // uk_proctor_events_client_event_id constraint is what actually guarantees
    // an event is stored once, and losing that race is a DUPLICATE - which is
    // what the caller was promised - not a 500 that discards the whole batch.

    @Test
    void aDuplicateInsideOneBatchIsCountedNotStoredTwice() throws Exception {
        // The pre-read cannot catch this: neither copy is stored yet.
        String body = batchJson("evt-1", "evt-2", "evt-1");

        upload(candidateToken, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted", is(2)))
                .andExpect(jsonPath("$.duplicates", is(1)))
                .andExpect(jsonPath("$.total", is(3)));

        assertThat(events.findBySessionIdOrderByStartTimeAsc(sessionId)).hasSize(2);
    }

    @Test
    void overlappingBatchesNeverProduceAnErrorOrADoubleRow() throws Exception {
        // Two clients uploading batches that share an id, hitting the server at
        // the same time. Whichever ordering wins, the result must be the same.
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var barrier = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<Integer> send = () -> {
                barrier.await();
                return upload(candidateToken, batchJson("evt-1", "evt-2", "evt-3"))
                        .andReturn().getResponse().getStatus();
            };

            var first = pool.submit(send);
            var second = pool.submit(send);
            barrier.countDown();

            assertThat(first.get()).isEqualTo(200);
            assertThat(second.get()).isEqualTo(200);
        } finally {
            pool.shutdownNow();
        }

        // Stored exactly once, whichever way the race went.
        assertThat(events.findBySessionIdOrderByStartTimeAsc(sessionId)).hasSize(3);
        assertThat(events.findBySessionIdOrderByStartTimeAsc(sessionId))
                .extracting(ProctorEvent::getClientEventId)
                .doesNotHaveDuplicates();
    }

    @Test
    void theRetryPathStoresWhatIsNewAndConcedesWhatIsNot() {
        // Drives the recovery bean directly: the fast path is rare enough that
        // provoking it reliably through HTTP is not worth the flakiness, but
        // the behaviour it falls back to must still be pinned.
        var batch = json.readValue(batchJson("evt-1", "evt-2"), ProctorEventBatch.class);

        var firstPass = retryService.ingestOneByOne(sessionId, batch);
        assertThat(firstPass.accepted()).isEqualTo(2);
        assertThat(firstPass.duplicates()).isZero();

        var secondPass = retryService.ingestOneByOne(sessionId, batch);
        assertThat(secondPass.accepted()).isZero();
        assertThat(secondPass.duplicates()).isEqualTo(2);

        assertThat(events.findBySessionIdOrderByStartTimeAsc(sessionId)).hasSize(2);
    }

    @Test
    void aLargeBatchIsCheckedInOneQueryAndStillDeduplicates() throws Exception {
        String[] ids = new String[50];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = "bulk-" + i;
        }
        upload(candidateToken, batchJson(ids)).andExpect(jsonPath("$.accepted", is(50)));

        // The same 50 plus 5 new ones.
        String[] mixed = new String[55];
        System.arraycopy(ids, 0, mixed, 0, 50);
        for (int i = 0; i < 5; i++) {
            mixed[50 + i] = "extra-" + i;
        }
        upload(candidateToken, batchJson(mixed))
                .andExpect(jsonPath("$.accepted", is(5)))
                .andExpect(jsonPath("$.duplicates", is(50)));

        assertThat(events.findBySessionIdOrderByStartTimeAsc(sessionId)).hasSize(55);
    }
}
