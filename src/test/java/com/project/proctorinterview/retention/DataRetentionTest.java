package com.project.proctorinterview.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.project.proctorinterview.TestDatabaseCleaner;
import com.project.proctorinterview.answer.Answer;
import com.project.proctorinterview.answer.AnswerRepository;
import com.project.proctorinterview.auth.AppUserDetails;
import com.project.proctorinterview.common.ApiException;
import com.project.proctorinterview.common.Enums.CandidateType;
import com.project.proctorinterview.common.Enums.Difficulty;
import com.project.proctorinterview.common.Enums.EvaluatorType;
import com.project.proctorinterview.common.Enums.InterviewStatus;
import com.project.proctorinterview.common.Enums.InterviewType;
import com.project.proctorinterview.common.Enums.ProctorEventType;
import com.project.proctorinterview.common.Enums.QuestionSource;
import com.project.proctorinterview.common.Enums.Recommendation;
import com.project.proctorinterview.common.Enums.ReviewDecision;
import com.project.proctorinterview.common.Enums.Role;
import com.project.proctorinterview.common.Enums.SessionStatus;
import com.project.proctorinterview.interview.Interview;
import com.project.proctorinterview.interview.InterviewRepository;
import com.project.proctorinterview.interview.InterviewSession;
import com.project.proctorinterview.interview.InterviewSessionRepository;
import com.project.proctorinterview.proctor.ProctorEvent;
import com.project.proctorinterview.proctor.ProctorEventRepository;
import com.project.proctorinterview.question.Question;
import com.project.proctorinterview.question.QuestionRepository;
import com.project.proctorinterview.report.Report;
import com.project.proctorinterview.report.ReportRepository;
import com.project.proctorinterview.report.ReportReviewRepository;
import com.project.proctorinterview.report.ReportReviewService;
import com.project.proctorinterview.retention.DataDeletion.Scope;
import com.project.proctorinterview.user.User;
import com.project.proctorinterview.user.UserRepository;

/**
 * Erasing a candidate's data.
 *
 * <p>The claim under test is a strong one - "your data is gone" - so most of
 * these check that nothing is left behind rather than that the happy path
 * returns something. The other half check that the erasure log does not itself
 * become a place the identity survives.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DataRetentionTest {

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
    private QuestionRepository questions;
    @Autowired
    private AnswerRepository answers;
    @Autowired
    private ProctorEventRepository proctorEvents;
    @Autowired
    private ReportRepository reports;
    @Autowired
    private ReportReviewRepository reviewRepo;
    @Autowired
    private ReportReviewService reviewService;
    @Autowired
    private DataDeletionRepository deletions;
    @Autowired
    private DataRetentionService service;

    private User adminUser;
    private AppUserDetails admin;
    private AppUserDetails recruiter;
    private User candidate;

    @BeforeEach
    void setUp() {
        cleaner.clean();
        adminUser = save("admin@test.local", Role.ADMIN);
        admin = new AppUserDetails(adminUser);
        recruiter = new AppUserDetails(save("rec@test.local", Role.RECRUITER));
        candidate = save("cand@test.local", Role.CANDIDATE);
        fullInterview();
    }

    private User save(String email, Role role) {
        User u = new User();
        u.setEmail(email);
        u.setPasswordHash("$2a$10$notarealhash");
        u.setFullName("Test " + role);
        u.setRole(role);
        u.setEnabled(true);
        return users.save(u);
    }

    /** One complete interview: session, questions, answers, events, report, review. */
    private InterviewSession fullInterview() {
        Interview interview = new Interview();
        interview.setInterviewName("Java Developer");
        interview.setRecruiter(users.findById(recruiter.getId()).orElseThrow());
        interview.setCandidate(candidate);
        interview.setScheduledAt(Instant.now().minus(Duration.ofDays(2)));
        interview.setCandidateType(CandidateType.FRESHER);
        interview.setDomain("Java");
        interview.setInterviewType(InterviewType.TECHNICAL);
        interview.setQuestionCount(2);
        interview.setStatus(InterviewStatus.COMPLETED);
        interview.setInviteToken(UUID.randomUUID().toString());
        interview.setResultVisibleToCandidate(true);
        interviews.save(interview);

        InterviewSession session = new InterviewSession();
        session.setInterview(interview);
        session.setStatus(SessionStatus.COMPLETED);
        session.setStartedAt(Instant.now().minus(Duration.ofDays(2)));
        session.setEndedAt(Instant.now().minus(Duration.ofDays(2)).plusSeconds(600));
        sessions.save(session);

        for (int i = 1; i <= 2; i++) {
            Question q = new Question();
            q.setSession(session);
            q.setSequenceNo(i);
            q.setText("Question " + i + " about Java");
            q.setDifficulty(Difficulty.MEDIUM);
            q.setSource(QuestionSource.BANK);
            questions.save(q);

            Answer a = new Answer();
            a.setQuestion(q);
            a.setRawTranscript("Something the candidate said, verbatim, number " + i);
            a.setCleanTranscript("Something the candidate said number " + i);
            a.setFillerCount(0);
            a.setWordCount(7);
            a.setDurationSeconds(30);
            a.setTechnicalScore(70);
            a.setRelevanceScore(70);
            a.setProblemSolvingScore(70);
            a.setCommunicationScore(70);
            a.setOverallScore(70);
            a.setEvaluator(EvaluatorType.FALLBACK);
            answers.save(a);
        }

        ProctorEvent event = new ProctorEvent();
        event.setSession(session);
        event.setEventType(ProctorEventType.NO_FACE);
        event.setStartTime(Instant.now().minus(Duration.ofDays(2)));
        event.setEndTime(Instant.now().minus(Duration.ofDays(2)).plusSeconds(4));
        event.setDurationMs(4000L);
        event.setConfidence(new BigDecimal("0.900"));
        event.setClientEventId(UUID.randomUUID().toString());
        proctorEvents.save(event);

        Report report = new Report();
        report.setSession(session);
        report.setTechnicalScore(70);
        report.setCommunicationScore(70);
        report.setProblemSolvingScore(70);
        report.setRelevanceScore(70);
        report.setOverallScore(70);
        report.setRecommendation(Recommendation.FURTHER_REVIEW);
        report.setExplanation("Middling.");
        report.setIntegrityFlag(false);
        reports.save(report);

        reviewService.record(session.getId(), adminUser.getId(), Role.ADMIN,
                ReviewDecision.ON_HOLD, "Waiting on references.");

        return session;
    }

    // ---- the preview --------------------------------------------------------

    @Test
    void thePreviewCountsEverythingThatWouldGo() {
        var preview = service.preview(candidate.getId());

        assertThat(preview.interviews()).isEqualTo(1);
        assertThat(preview.sessions()).isEqualTo(1);
        assertThat(preview.questions()).isEqualTo(2);
        assertThat(preview.answers()).isEqualTo(2);
        assertThat(preview.proctorEvents()).isEqualTo(1);
        assertThat(preview.reports()).isEqualTo(1);
        assertThat(preview.reviews()).isEqualTo(1);
        assertThat(preview.totalRows()).isEqualTo(9);
        assertThat(preview.isEmpty()).isFalse();
    }

    @Test
    void thePreviewChangesNothing() {
        service.preview(candidate.getId());

        // Looking is not deleting - worth pinning, because the preview walks
        // exactly the same rows the deletion does.
        assertThat(interviews.findAll()).hasSize(1);
        assertThat(answers.findAll()).hasSize(2);
        assertThat(reports.findAll()).hasSize(1);
    }

    @Test
    void aCandidateWithNoInterviewsPreviewsAsEmpty() {
        User fresh = save("nobody@test.local", Role.CANDIDATE);

        var preview = service.preview(fresh.getId());
        assertThat(preview.isEmpty()).isTrue();
        assertThat(preview.totalRows()).isZero();
    }

    // ---- the deletion -------------------------------------------------------

    @Test
    void erasingInterviewDataRemovesEveryTraceButKeepsTheAccount() {
        service.deleteCandidateData(candidate.getId(), adminUser, Scope.INTERVIEW_DATA_ONLY,
                "cand@test.local", "Erasure request #12");

        assertThat(interviews.findAll()).isEmpty();
        assertThat(sessions.findAll()).isEmpty();
        assertThat(questions.findAll()).isEmpty();
        assertThat(answers.findAll()).isEmpty();
        assertThat(proctorEvents.findAll()).isEmpty();
        assertThat(reports.findAll()).isEmpty();
        assertThat(reviewRepo.findAll()).isEmpty();

        // The account stays: this scope is "clear their history", not "remove them".
        assertThat(users.findById(candidate.getId())).isPresent();
    }

    @Test
    void erasingTheAccountRemovesTheUserToo() {
        service.deleteCandidateData(candidate.getId(), adminUser,
                Scope.INTERVIEW_DATA_AND_ACCOUNT, "cand@test.local", null);

        assertThat(users.findById(candidate.getId())).isEmpty();
        assertThat(interviews.findAll()).isEmpty();
        assertThat(answers.findAll()).isEmpty();
    }

    @Test
    void theTranscriptIsGoneNotJustTheScores() {
        // The most personal thing stored is what the candidate actually said.
        assertThat(answers.findAll()).isNotEmpty();

        service.deleteCandidateData(candidate.getId(), adminUser, Scope.INTERVIEW_DATA_ONLY,
                "cand@test.local", null);

        assertThat(answers.findAll()).isEmpty();
    }

    @Test
    void anotherCandidatesDataIsUntouched() {
        User other = save("other@test.local", Role.CANDIDATE);
        Interview theirs = new Interview();
        theirs.setInterviewName("Other");
        theirs.setRecruiter(users.findById(recruiter.getId()).orElseThrow());
        theirs.setCandidate(other);
        theirs.setScheduledAt(Instant.now());
        theirs.setCandidateType(CandidateType.FRESHER);
        theirs.setDomain("Java");
        theirs.setInterviewType(InterviewType.TECHNICAL);
        theirs.setQuestionCount(1);
        theirs.setStatus(InterviewStatus.SCHEDULED);
        theirs.setInviteToken(UUID.randomUUID().toString());
        interviews.save(theirs);

        service.deleteCandidateData(candidate.getId(), adminUser, Scope.INTERVIEW_DATA_ONLY,
                "cand@test.local", null);

        assertThat(interviews.findAll()).hasSize(1);
        assertThat(interviews.findAll().getFirst().getCandidate().getId()).isEqualTo(other.getId());
    }

    // ---- the confirmation ---------------------------------------------------

    @Test
    void aWrongConfirmationEmailDeletesNothing() {
        assertThatThrownBy(() -> service.deleteCandidateData(candidate.getId(), adminUser,
                Scope.INTERVIEW_DATA_ONLY, "someone.else@test.local", null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Type the candidate's email address");

        assertThat(interviews.findAll()).hasSize(1);
        assertThat(answers.findAll()).hasSize(2);
    }

    @Test
    void aMissingConfirmationDeletesNothing() {
        assertThatThrownBy(() -> service.deleteCandidateData(candidate.getId(), adminUser,
                Scope.INTERVIEW_DATA_ONLY, null, null))
                .isInstanceOf(ApiException.class);

        assertThat(answers.findAll()).hasSize(2);
    }

    @Test
    void theConfirmationIgnoresCaseAndSurroundingSpace() {
        // A typed address should not fail on a capital or a trailing space.
        service.deleteCandidateData(candidate.getId(), adminUser, Scope.INTERVIEW_DATA_ONLY,
                "  Cand@Test.Local  ", null);

        assertThat(interviews.findAll()).isEmpty();
    }

    // ---- who may be erased --------------------------------------------------

    @Test
    void staffCannotBeErasedHere() {
        // A recruiter owns interviews rather than being their subject, so
        // removing one would orphan other people's records.
        assertThatThrownBy(() -> service.preview(recruiter.getId()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Only a candidate's data");

        assertThatThrownBy(() -> service.deleteCandidateData(recruiter.getId(), adminUser,
                Scope.INTERVIEW_DATA_AND_ACCOUNT, "rec@test.local", null))
                .isInstanceOf(ApiException.class);
    }

    // ---- the log ------------------------------------------------------------

    @Test
    void theLogRecordsWhatHappenedWithoutKeepingTheIdentity() {
        service.deleteCandidateData(candidate.getId(), adminUser, Scope.INTERVIEW_DATA_AND_ACCOUNT,
                "cand@test.local", "Erasure request #12");

        var record = deletions.findAll().getFirst();

        assertThat(record.getSubjectUserId()).isEqualTo(candidate.getId());
        assertThat(record.getScope()).isEqualTo(Scope.INTERVIEW_DATA_AND_ACCOUNT);
        assertThat(record.getPerformedBy().getId()).isEqualTo(adminUser.getId());
        assertThat(record.getReason()).isEqualTo("Erasure request #12");
        assertThat(record.getAnswersDeleted()).isEqualTo(2);
        assertThat(record.getReportsDeleted()).isEqualTo(1);

        // The whole point: an erasure request must not leave the identity
        // behind in a new table.
        assertThat(record).hasNoNullFieldsOrPropertiesExcept("reason");
        assertThat(record.toString()).doesNotContain("cand@test.local");
    }

    @Test
    void theLogSurvivesTheAccountItDescribes() {
        service.deleteCandidateData(candidate.getId(), adminUser, Scope.INTERVIEW_DATA_AND_ACCOUNT,
                "cand@test.local", null);

        // No FK to the subject, so the record outlives them - which is what
        // makes it evidence rather than a dangling reference.
        assertThat(users.findById(candidate.getId())).isEmpty();
        assertThat(deletions.recentFirst()).hasSize(1);
    }

    // ---- retention overview -------------------------------------------------

    @Test
    void theOverviewReportsWithoutDeletingAnything() {
        var overview = service.retentionOverview();

        assertThat(overview.months()).isEqualTo(24);
        assertThat(overview.interviewsTotal()).isEqualTo(1);
        // Two days old, so well inside a 24-month window.
        assertThat(overview.interviewsOlder()).isZero();
        assertThat(overview.anythingOld()).isFalse();

        assertThat(interviews.findAll()).hasSize(1);
    }

    @Test
    void anOldInterviewIsCountedButNotRemoved() {
        Interview old = interviews.findAll().getFirst();
        old.setScheduledAt(Instant.now().minus(Duration.ofDays(365 * 3)));
        interviews.save(old);

        var overview = service.retentionOverview();
        assertThat(overview.interviewsOlder()).isEqualTo(1);
        assertThat(overview.anythingOld()).isTrue();

        // Reported, not acted on. Nothing here runs on a schedule.
        assertThat(interviews.findAll()).hasSize(1);
    }

    // ---- the pages ----------------------------------------------------------

    @Test
    void thePreviewPageShowsTheCountsBeforeTheForm() throws Exception {
        mvc.perform(get("/admin/users/{id}/data", candidate.getId()).with(user(admin)))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("What is stored")))
                .andExpect(content().string(Matchers.containsString("cand@test.local")))
                .andExpect(content().string(Matchers.containsString("This cannot be undone")));
    }

    @Test
    void theRetentionPageRendersForAnAdmin() throws Exception {
        mvc.perform(get("/admin/data-retention").with(user(admin)))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Retention window")))
                .andExpect(content().string(Matchers.containsString("Nothing has been erased")));
    }

    @Test
    void theRetentionPageRendersTheLogItselfNotJustTheEmptyState() throws Exception {
        // Regression: the first version of this test only ever rendered the
        // page with an EMPTY log, so a template that could not read a row went
        // unnoticed until a real browser hit it. Worse, it failed AFTER the
        // response was committed, producing a half-written page rather than an
        // error - the trap documented in CLAUDE.md.
        service.deleteCandidateData(candidate.getId(), adminUser, Scope.INTERVIEW_DATA_AND_ACCOUNT,
                "cand@test.local", "Erasure request #7");

        String page = mvc.perform(get("/admin/data-retention").with(user(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(page).contains("Erasure request #7");
        assertThat(page).contains("Interview data and the account");
        assertThat(page).contains("user #" + candidate.getId());
        assertThat(page).doesNotContain("Nothing has been erased");

        // The design of the table, visible on the page: no identity survives.
        assertThat(page).doesNotContain("cand@test.local");
    }

    @Test
    void theDeletionLogIsMappedToRecordsNotEntities() {
        service.deleteCandidateData(candidate.getId(), adminUser, Scope.INTERVIEW_DATA_ONLY,
                "cand@test.local", "why");

        // Resolved inside the service transaction, so nothing lazy is left for
        // a template to trip over - open-in-view is off.
        var entries = service.deletionLog();
        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst().performedByName()).isEqualTo("Test ADMIN");
        assertThat(entries.getFirst().scopeLabel()).isEqualTo("Interview data only");
        assertThat(entries.getFirst().totalRows()).isEqualTo(9);
        assertThat(entries.getFirst().deletedAtText()).isNotBlank();
    }

    @Test
    void aRecruiterCannotReachEitherPage() throws Exception {
        mvc.perform(get("/admin/data-retention").with(user(recruiter)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/admin/users/{id}/data", candidate.getId()).with(user(recruiter)))
                .andExpect(status().isForbidden());
    }

    @Test
    void theFormErasesAndReportsWhatWent() throws Exception {
        mvc.perform(post("/admin/users/{id}/data/delete", candidate.getId())
                        .with(user(admin)).with(csrf())
                        .param("scope", "INTERVIEW_DATA_ONLY")
                        .param("confirmEmail", "cand@test.local")
                        .param("reason", "Requested by the candidate"))
                .andExpect(status().is3xxRedirection());

        assertThat(interviews.findAll()).isEmpty();
        assertThat(deletions.findAll()).hasSize(1);
    }

    @Test
    void theDeletionPostRequiresACsrfToken() throws Exception {
        mvc.perform(post("/admin/users/{id}/data/delete", candidate.getId())
                        .with(user(admin))
                        .param("scope", "INTERVIEW_DATA_ONLY")
                        .param("confirmEmail", "cand@test.local"))
                .andExpect(status().isForbidden());

        assertThat(interviews.findAll()).hasSize(1);
    }

    @Test
    void aBadConfirmationThroughTheFormReturnsToThePreview() throws Exception {
        mvc.perform(post("/admin/users/{id}/data/delete", candidate.getId())
                        .with(user(admin)).with(csrf())
                        .param("scope", "INTERVIEW_DATA_ONLY")
                        .param("confirmEmail", "wrong@test.local"))
                .andExpect(status().is3xxRedirection());

        assertThat(interviews.findAll()).hasSize(1);
        assertThat(deletions.findAll()).isEmpty();
    }

    @Test
    void theUsersListOffersTheDataLinkOnlyForCandidates() throws Exception {
        String page = mvc.perform(get("/admin/users").with(user(admin)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(page).contains("/admin/users/" + candidate.getId() + "/data");
        assertThat(page).doesNotContain("/admin/users/" + recruiter.getId() + "/data");
    }
}
